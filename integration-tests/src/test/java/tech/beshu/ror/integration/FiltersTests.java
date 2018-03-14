package tech.beshu.ror.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer.create;

import java.util.Optional;
import java.util.Random;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.junit.ClassRule;
import org.junit.Test;

import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer;
import tech.beshu.ror.utils.gradle.RorPluginGradleProject;
import tech.beshu.ror.utils.httpclient.RestClient;

public class FiltersTests {

	private static final String IDX_PREFIX = "testfilter";

	private static RestClient adminClient;
	@ClassRule
	public static ESWithReadonlyRestContainer container = create(RorPluginGradleProject.fromSystemProperty(),
			"/filters/elasticsearch.yml", Optional.of(c -> {
				insertDoc("a1", c, "a", "title");
				insertDoc("a2", c, "a", "title");
				insertDoc("b1", c, "bandc", "title");
				insertDoc("b2", c, "bandc", "title");
				insertDoc("c1", c, "bandc", "title");
				insertDoc("c2", c, "bandc", "title");
				insertDoc("d1", c, "d", "title");
				insertDoc("d2", c, "d", "title");
				insertDoc("d1", c, "d", "nottitle");
				insertDoc("d2", c, "d", "nottitle");
			})

	);

	private static void insertDoc(String docName, RestClient restClient, String idx, String field) {
		if (adminClient == null) {
			adminClient = restClient;
		}

		String path = "/" + IDX_PREFIX + idx + "/documents/doc-" + docName + String.valueOf(Math.random());
		try {

			HttpPut request = new HttpPut(restClient.from(path));
			request.setHeader("Content-Type", "application/json");
			request.setHeader("refresh", "true");
			request.setHeader("timeout", "50s");
			request.setEntity(new StringEntity("{\"" + field + "\": \"" + docName + "\"}"));
			System.out.println(body(restClient.execute(request)));

		} catch (Exception e) {
			e.printStackTrace();
			throw new IllegalStateException("Test problem", e);
		}

		// Polling phase.. #TODO is there a better way?
		try {
			HttpResponse response;
			do {
				HttpHead request = new HttpHead(restClient.from(path));
				request.setHeader("x-api-key", "p");
				response = restClient.execute(request);
				System.out
						.println("polling for " + docName + ".. result: " + response.getStatusLine().getReasonPhrase());
				Thread.sleep(200);
			} while (response.getStatusLine().getStatusCode() != 200);
		} catch (Exception e) {
			e.printStackTrace();
			throw new IllegalStateException("Cannot configure test case", e);
		}
	}

	private static String body(HttpResponse r) throws Exception {
		return EntityUtils.toString(r.getEntity());
	}
	
	@Test
	public void testDirectSingleIdxa() throws Exception {
		String body = search("/" + IDX_PREFIX + "a/_search");
		assertTrue(body.contains("a1"));
		assertFalse(body.contains("a2"));
		assertFalse(body.contains("b1"));
		assertFalse(body.contains("b2"));
		assertFalse(body.contains("c1"));
		assertFalse(body.contains("c2"));
	}
	
	@Test
	public void testDirectMultipleIdxbandc() throws Exception {
		String body = search("/" + IDX_PREFIX + "bandc/_search");
		assertFalse(body.contains("a1"));
		assertFalse(body.contains("a2"));
		assertTrue(body.contains("b1"));
		assertFalse(body.contains("b2"));
		assertFalse(body.contains("c1"));
		assertTrue(body.contains("c2"));
	}
	
	@Test
	public void testDirectSingleIdxd() throws Exception {
		String body = search("/" + IDX_PREFIX + "d/_search");
		assertFalse(body.contains("a1"));
		assertFalse(body.contains("a2"));
		assertFalse(body.contains("b1"));
		assertFalse(body.contains("b2"));
		assertFalse(body.contains("c1"));
		assertFalse(body.contains("c2"));
		assertTrue(body.contains("\"title\": \"d1\""));
		assertFalse(body.contains("\"title\": \"d2\""));
		assertFalse(body.contains("\"nottitle\": \"d1\""));
		assertFalse(body.contains("\"nottitle\": \"d2\""));
	}
	
	private String search(String endpoint) throws Exception {
	    HttpGet request = new HttpGet(adminClient.from(endpoint));

	    request.setHeader("timeout", "50s");
	    String caller = Thread.currentThread().getStackTrace()[2].getMethodName();
	    request.setHeader("x-caller-" + caller, "true");
	    request.setHeader("x-api-key", "g");

	    HttpResponse resp = adminClient.execute(request);

	    String body = body(resp);
	    System.out.println("SEARCH RESPONSE for " + caller + ": " + body);
	    assertEquals(200, resp.getStatusLine().getStatusCode());
	    return body;
	  }
}
