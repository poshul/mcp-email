/*
 * Copyright (C) 2026 Sam Wein
 *
 * This software is free:
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESSED OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mcp.mail;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.stream.Collectors;

@ApplicationScoped
public class AttachmentArchiveService {
	@ConfigProperty(name = "email.allow-archiving", defaultValue = "false")
	boolean allowArchiving;

	@ConfigProperty(name = "email.archive.webdav.url")
	Optional<String> webdavUrl = Optional.empty();

	@ConfigProperty(name = "email.archive.webdav.username")
	Optional<String> username = Optional.empty();

	@ConfigProperty(name = "email.archive.webdav.password")
	Optional<String> password = Optional.empty();

	@ConfigProperty(name = "email.archive.root", defaultValue = "/Travel")
	String root = "/Travel";

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
			.followRedirects(HttpClient.Redirect.NEVER).build();

	// Validate all configuration and caller input before retrieving any attachment.
	URI destination(String path) {
		if (!allowArchiving) {
			throw new IllegalArgumentException("Archiving is disabled. Set EMAIL_ALLOW_ARCHIVING=true to enable it.");
		}
		if (webdavUrl.filter(s -> !s.isBlank()).isEmpty() || username.filter(s -> !s.isBlank()).isEmpty()
				|| password.filter(s -> !s.isBlank()).isEmpty()) {
			throw new IllegalArgumentException("Configure EMAIL_ARCHIVE_WEBDAV_URL, "
					+ "EMAIL_ARCHIVE_WEBDAV_USERNAME and EMAIL_ARCHIVE_WEBDAV_PASSWORD before archiving.");
		}
		URI base;
		try {
			base = URI.create(webdavUrl.get());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("EMAIL_ARCHIVE_WEBDAV_URL must be a valid HTTPS WebDAV endpoint URL.");
		}
		if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null || base.getRawUserInfo() != null
				|| base.getRawQuery() != null || base.getRawFragment() != null) {
			throw new IllegalArgumentException(
					"EMAIL_ARCHIVE_WEBDAV_URL must be an HTTPS WebDAV endpoint URL without credentials, query or fragment.");
		}
		if (username.get().contains(":") || username.get().codePoints().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException(
					"WebDAV Basic authentication username must not contain a colon or control characters.");
		}
		String normalized = validatePath(root, path);
		return URI.create(base.toASCIIString().replaceAll("/+$", "") + encodePath(normalized));
	}

	String normalizedPath(String path) {
		return validatePath(root, path);
	}

	static String validatePath(String root, String path) {
		String normalizedRoot = canonicalPath(root);
		String normalized = canonicalPath(path);
		if (!normalized.startsWith(normalizedRoot + "/")) {
			throw new IllegalArgumentException("destinationPath must name a file within EMAIL_ARCHIVE_ROOT.");
		}
		return normalized;
	}

	private static String canonicalPath(String path) {
		if (path == null || !path.startsWith("/") || path.endsWith("/")) {
			throw new IllegalArgumentException(
					"Archive paths must be absolute with a nonempty final component; the endpoint root is forbidden.");
		}
		String normalized = Normalizer.normalize(path, Normalizer.Form.NFC);
		for (String part : normalized.substring(1).split("/", -1))
			validateComponent(part);
		return normalized;
	}

	private static void validateComponent(String part) {
		// Accept literal names only, never pre-encoded paths. Reject compatibility
		// normalization changes as well, so alternate dots/separators cannot escape.
		if (part.isBlank() || !part.equals(part.strip()) || part.equals(".") || part.equals("..") || part.contains("%")
				|| part.contains("\\") || part.contains("/")
				|| part.codePoints().anyMatch(c -> Character.isISOControl(c) || (c >= 0xD800 && c <= 0xDFFF))
				|| !Normalizer.normalize(part, Normalizer.Form.NFKC).equals(part)) {
			throw new IllegalArgumentException(
					"Invalid archive path component (encoded paths and traversal are forbidden).");
		}
	}

	private static String encode(String component) {
		return URLEncoder.encode(component, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static String encodePath(String path) {
		return Arrays.stream(path.split("/", -1)).map(AttachmentArchiveService::encode)
				.collect(Collectors.joining("/"));
	}

	String archive(URI destination, String path, EmailService.AttachmentContent attachment)
			throws IOException, InterruptedException {
		String hash = sha256(attachment.data());
		var existing = send(destination, "GET", null);
		boolean alreadyPresent = existing.statusCode() == 200;
		if (alreadyPresent) {
			verifyIdentical(existing.body(), hash);
		} else {
			requireStatus(existing.statusCode(), 404, "checking destination");
			// The URI is built from a validated root and path. Create collections
			// below the configured WebDAV endpoint, never the endpoint itself.
			String url = destination.toASCIIString();
			int slash = url.length() - encodePath(path).length();
			while ((slash = url.indexOf('/', slash + 1)) >= 0) {
				var directory = send(URI.create(url.substring(0, slash)), "MKCOL", null);
				if (directory.statusCode() != 201 && directory.statusCode() != 405) {
					throw new ArchiveException(
							"WebDAV failed creating parent directory (HTTP " + directory.statusCode() + ").");
				}
			}
			var uploaded = send(destination, "PUT", attachment.data());
			if (uploaded.statusCode() == 412) {
				// Someone created the file after our GET. Never overwrite it.
				var raced = send(destination, "GET", null);
				requireStatus(raced.statusCode(), 200, "checking concurrent upload");
				verifyIdentical(raced.body(), hash);
				alreadyPresent = true;
			} else if (uploaded.statusCode() != 201 && uploaded.statusCode() != 204) {
				throw new ArchiveException("WebDAV failed uploading attachment (HTTP " + uploaded.statusCode() + ").");
			}
		}
		return (alreadyPresent ? "Attachment already archived identically: " : "Archived ") + "\""
				+ attachment.fileName() + "\" (" + attachment.mimeType() + ", " + attachment.data().length
				+ " bytes, sha256=" + hash + ") to " + path;
	}

	private HttpResponse<byte[]> send(URI uri, String method, byte[] data) throws IOException, InterruptedException {
		String credentials = username.orElseThrow() + ":" + password.orElseThrow();
		var request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).header("Authorization",
				"Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
		if (data != null)
			request.header("If-None-Match", "*").header("Content-Type", "application/octet-stream");
		return client.send(
				request.method(method,
						data == null ? HttpRequest.BodyPublishers.noBody()
								: HttpRequest.BodyPublishers.ofByteArray(data))
						.build(),
				HttpResponse.BodyHandlers.ofByteArray());
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable");
		}
	}

	private static void verifyIdentical(byte[] existing, String expectedHash) throws IOException {
		if (!sha256(existing).equals(expectedHash)) {
			throw new ArchiveException("Destination exists with different contents; nothing was overwritten.");
		}
	}

	static class ArchiveException extends IOException {
		ArchiveException(String message) {
			super(message);
		}
	}

	private static void requireStatus(int actual, int expected, String operation) throws IOException {
		if (actual != expected)
			throw new ArchiveException("WebDAV failed " + operation + " (HTTP " + actual + ").");
	}
}
