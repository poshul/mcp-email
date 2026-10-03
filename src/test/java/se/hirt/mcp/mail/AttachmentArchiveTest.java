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

import com.sun.net.httpserver.HttpServer;
import io.quarkiverse.mcp.server.ToolCallException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentArchiveTest {
	private static final String DAV = "/storage/team%20files";
	private static final String PATH = "/Travel/2026/ASMS/attachments/invoice.pdf";
	private static final byte[] SOURCE = {0, 1, 2, 13, 10, (byte) 255};
	private final Map<String, byte[]> files = new HashMap<>();
	private final Set<String> directories = new HashSet<>();
	private final List<String> requests = new ArrayList<>();
	private final List<String> mkdirs = new ArrayList<>();
	private final List<String> auth = new ArrayList<>();
	private HttpServer server;
	private EmailTools tool;
	private AttachmentArchiveService service;
	private int fetches;
	private int puts;
	private String condition;
	private byte[] race;
	private int forcedStatus;

	@BeforeEach
	void setup() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		directories.add(DAV);
		server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getRawPath();
			String method = exchange.getRequestMethod();
			requests.add(method + " " + path);
			auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
			byte[] response = new byte[0];
			int status;
			if (forcedStatus != 0) {
				status = forcedStatus;
				response = "SECRET SERVER BODY".getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Location", "/should-not-follow");
			} else if (method.equals("GET")) {
				response = files.getOrDefault(path, new byte[0]);
				status = files.containsKey(path) ? 200 : 404;
			} else if (method.equals("MKCOL")) {
				mkdirs.add(path);
				if (!directories.contains(path.substring(0, path.lastIndexOf('/'))))
					status = 409;
				else
					status = directories.add(path) ? 201 : 405;
			} else if (method.equals("PUT")) {
				puts++;
				condition = exchange.getRequestHeaders().getFirst("If-None-Match");
				if (race != null)
					files.put(path, race);
				if (files.containsKey(path) && "*".equals(condition))
					status = 412;
				else if (!directories.contains(path.substring(0, path.lastIndexOf('/'))))
					status = 409;
				else {
					files.put(path, exchange.getRequestBody().readAllBytes());
					status = 201;
				}
			} else
				status = 500;
			exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();
		// Only the test adapter changes transport to loopback HTTP, after executing
		// production HTTPS/config/path validation. Production has no HTTP opt-out.
		service = new AttachmentArchiveService() {
			@Override
			URI destination(String path) {
				URI checked = super.destination(path);
				return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + checked.getRawPath());
			}
		};
		service.allowArchiving = true;
		service.webdavUrl = Optional.of("https://cloud.example.com" + DAV);
		service.username = Optional.of("test user");
		service.password = Optional.of("secret-password");
		tool = new EmailTools();
		tool.archiveService = service;
		tool.emailService = new EmailService() {
			@Override
			public AttachmentContent getAttachment(String account, String folder, long uid, String name) {
				assertEquals("home", account);
				assertEquals("INBOX", folder);
				assertEquals(42, uid);
				assertEquals("invoice.pdf", name);
				fetches++;
				return new AttachmentContent(name, "application/pdf", SOURCE);
			}
		};
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private String archive(String path) {
		return tool.archiveAttachment("home", "INBOX", 42, "invoice.pdf", path);
	}

	private String archiveError(String path) {
		return assertThrows(ToolCallException.class, () -> archive(path)).getMessage();
	}

	@Test
	void uploadsExactBytesAndReturnsOnlyMetadata() {
		String result = archive(PATH);
		assertArrayEquals(SOURCE, files.get(DAV + PATH));
		assertEquals("*", condition);
		assertEquals("Archived \"invoice.pdf\" (application/pdf, 6 bytes, sha256="
				+ "b9bdb04937ab74bc792feebc816ea37193b3a56df1af99210f580e35ea6a61cb) to " + PATH, result);
		String expectedAuth = "Basic "
				+ Base64.getEncoder().encodeToString("test user:secret-password".getBytes(StandardCharsets.UTF_8));
		assertTrue(auth.stream().allMatch(expectedAuth::equals));
		assertEquals(1, fetches);
	}

	@Test
	void createsParentsInOrderAndAcceptsExistingCollections() {
		directories.add(DAV + "/Travel");
		assertTrue(archive(PATH).startsWith("Archived"));
		assertEquals(List.of(DAV + "/Travel", DAV + "/Travel/2026", DAV + "/Travel/2026/ASMS",
				DAV + "/Travel/2026/ASMS/attachments"), mkdirs);
	}

	@Test
	void endpointTrailingSlashDoesNotChangeDestination() {
		service.webdavUrl = Optional.of("https://storage.example.com" + DAV + "/");
		assertTrue(archive(PATH).startsWith("Archived"));
		assertArrayEquals(SOURCE, files.get(DAV + PATH));
		assertFalse(mkdirs.contains(DAV));
		assertFalse(mkdirs.contains("/storage"));
	}

	@Test
	void endpointAtHostRootWorksWithOrWithoutTrailingSlash() {
		for (String suffix : List.of("", "/")) {
			service.webdavUrl = Optional.of("https://storage.example.com" + suffix);
			directories.add("");
			String path = "/Travel/host-root" + (suffix.isEmpty() ? "1" : "2") + ".pdf";
			assertTrue(archive(path).startsWith("Archived"));
			assertArrayEquals(SOURCE, files.get(path));
		}
		assertTrue(mkdirs.stream().allMatch(path -> path.equals("/Travel")));
	}

	@Test
	void explicitNextcloudEndpointStillWorks() {
		String endpoint = "/nextcloud/remote.php/dav/files/cloud%20owner";
		service.webdavUrl = Optional.of("https://cloud.example.com" + endpoint + "/");
		directories.add(endpoint);
		assertTrue(archive(PATH).startsWith("Archived"));
		assertArrayEquals(SOURCE, files.get(endpoint + PATH));
		assertTrue(mkdirs.stream().allMatch(path -> path.startsWith(endpoint + "/Travel")));
	}

	@Test
	void basicAuthUsernameIsNotTreatedAsAPathComponent() {
		service.username = Optional.of("tenant/user%name");
		assertTrue(archive(PATH).startsWith("Archived"));
		assertArrayEquals(SOURCE, files.get(DAV + PATH));
		String expectedAuth = "Basic " + Base64.getEncoder()
				.encodeToString("tenant/user%name:secret-password".getBytes(StandardCharsets.UTF_8));
		assertTrue(auth.stream().allMatch(expectedAuth::equals));
	}

	@Test
	void invalidEndpointConfigurationFailsBeforeFetching() {
		for (String url : List.of("not a url", "http://storage.example.com/dav", "/dav",
				"https://user:secret@storage.example.com/dav", "https://storage.example.com/dav?token=secret",
				"https://storage.example.com/dav#fragment")) {
			service.webdavUrl = Optional.of(url);
			String error = archiveError(PATH);
			assertTrue(error.contains("EMAIL_ARCHIVE_WEBDAV_URL"));
			assertFalse(error.contains("secret"));
		}
		assertEquals(0, fetches);
		assertTrue(requests.isEmpty());
	}

	@Test
	void invalidBasicAuthUsernameFailsBeforeFetching() {
		for (String username : List.of("user:other", "user\nother")) {
			service.username = Optional.of(username);
			assertTrue(archiveError(PATH).contains("Basic authentication username"));
		}
		assertEquals(0, fetches);
		assertTrue(requests.isEmpty());
	}

	@Test
	void identicalRetryDoesNotPutAgain() {
		archive(PATH);
		assertTrue(archive(PATH).startsWith("Attachment already archived identically:"));
		assertEquals(1, puts);
	}

	@Test
	void differingExistingFileIsNotOverwritten() {
		byte[] other = {9, 8, 7};
		files.put(DAV + PATH, other);
		assertTrue(archiveError(PATH).contains("different contents"));
		assertArrayEquals(other, files.get(DAV + PATH));
		assertEquals(0, puts);
		assertTrue(mkdirs.isEmpty());
	}

	@Test
	void concurrentIdenticalUploadIsIdempotent() {
		race = SOURCE;
		assertTrue(archive(PATH).startsWith("Attachment already archived identically:"));
		assertEquals("*", condition);
	}

	@Test
	void concurrentDifferentUploadIsNotOverwritten() {
		race = new byte[] {17};
		assertTrue(archiveError(PATH).contains("different contents"));
		assertArrayEquals(race, files.get(DAV + PATH));
	}

	@Test
	void rejectsOutsideRootAndNormalizationTricksBeforeFetching() {
		for (String path : List.of("/Elsewhere/file.pdf", "/Travel-other/file.pdf", "/file.pdf", "/Travel/../file.pdf",
				"/Travel/a/../../Travel/file.pdf", "/Travel/%2e%2e/file.pdf", "/Travel/%252e%252e/file.pdf",
				"/Travel/%2f../file.pdf", "/Travel/..\\file.pdf", "/Travel/．/file.pdf", "/Travel/．．/file.pdf",
				"/Travel/a／b/file.pdf", "/Travel//file.pdf", "/Travel/./file.pdf", "/Travel/", "/Travel", "",
				"file.pdf", "/Travel/a\nfile.pdf")) {
			assertTrue(archiveError(path).startsWith("Error"), path);
		}
		assertTrue(archiveError(null).startsWith("Error"));
		assertEquals(0, fetches);
		assertTrue(requests.isEmpty());
	}

	@Test
	void normalizesUnicodeAndEncodesIndividualComponents() {
		String path = "/Travel/cafe\u0301/receipt #1?.pdf";
		String result = archive(path);
		assertTrue(result.endsWith("/Travel/café/receipt #1?.pdf"));
		assertArrayEquals(SOURCE, files.get(DAV + "/Travel/caf%C3%A9/receipt%20%231%3F.pdf"));
	}

	@Test
	void disabledByDefault() throws Exception {
		assertEquals("false", AttachmentArchiveService.class.getDeclaredField("allowArchiving")
				.getAnnotation(ConfigProperty.class).defaultValue());
		tool.archiveService = new AttachmentArchiveService();
		assertTrue(archiveError(PATH).contains("EMAIL_ALLOW_ARCHIVING=true"));
		assertEquals(0, fetches);
		assertTrue(requests.isEmpty());
	}

	@Test
	void missingConfigurationIsClear() {
		for (int missing = 0; missing < 3; missing++) {
			service.webdavUrl = missing == 0 ? Optional.empty() : Optional.of("https://cloud.example.com" + DAV);
			service.username = missing == 1 ? Optional.empty() : Optional.of("test user");
			service.password = missing == 2 ? Optional.empty() : Optional.of("secret-password");
			assertTrue(archiveError(PATH).contains("Configure EMAIL_ARCHIVE_WEBDAV_URL"));
		}
		assertEquals(0, fetches);
	}

	@Test
	void requiresHttpsAndRejectsUserRootConfiguration() {
		service.webdavUrl = Optional.of("http://cloud.example.com");
		assertTrue(archiveError(PATH).contains("HTTPS"));
		service.webdavUrl = Optional.of("https://cloud.example.com" + DAV);
		service.root = "/";
		assertTrue(archiveError(PATH).startsWith("Error"));
		assertEquals(0, fetches);
	}

	@Test
	void customRootIsUsedForValidationAndMetadata() {
		service.root = "/Accounting";
		String path = "/Accounting/receipt.pdf";
		assertTrue(archive(path).endsWith("to " + path));
		assertArrayEquals(SOURCE, files.get(DAV + path));
		assertTrue(archiveError(PATH).startsWith("Error"));
	}

	@Test
	void arbitraryMimeTypeIsPreservedWithoutParsing() {
		tool.emailService = new EmailService() {
			@Override
			public AttachmentContent getAttachment(String account, String folder, long uid, String name) {
				return new AttachmentContent("data.bin", "application/x-custom", SOURCE);
			}
		};
		assertTrue(archive(PATH).contains("\"data.bin\" (application/x-custom, 6 bytes"));
		assertArrayEquals(SOURCE, files.get(DAV + PATH));
	}

	@Test
	void errorsAndRedirectsNeverExposeResponseBodyOrFollowRedirect() {
		for (int status : List.of(401, 403, 500, 302)) {
			forcedStatus = status;
			String result = archiveError(PATH);
			assertTrue(result.contains("HTTP " + status));
			assertFalse(result.contains("SECRET"));
			assertFalse(result.contains("secret-password"));
		}
		assertEquals(4, requests.size());
		assertEquals(0, puts);
	}

	@Test
	void unexpectedRetrievalErrorCannotLeakContents() {
		tool.emailService = new EmailService() {
			@Override
			public AttachmentContent getAttachment(String account, String folder, long uid, String name) {
				throw new IllegalArgumentException("SECRET ATTACHMENT CONTENTS");
			}
		};
		String result = archiveError(PATH);
		assertTrue(result.startsWith("Error"));
		assertFalse(result.contains("SECRET"));
		assertTrue(requests.isEmpty());
	}
}
