package pos.cloud.client

import kotlin.test.*

class CloudEndpointTest {
    @Test fun endpointRequiresHttpsExceptExplicitLocalDevelopment() {
        assertEquals("https://example.test",CloudEndpoint.parse("https://EXAMPLE.test/").url)
        listOf("http://example.test","http://127.0.0.1","https://user:password@example.test","https://example.test/path","https://example.test?q=secret","https://example.test/#fragment").forEach {assertFails {CloudEndpoint.parse(it)}}
        assertEquals("http://10.0.2.2:8080",CloudEndpoint.parse("http://10.0.2.2:8080",true).url)
        assertFails {CloudEndpoint.parse("http://example.test",true)}
    }
    @Test fun checkpointsPersistIdentityAndRejectCorruptionRatherThanResetting() {
        val files=PrivateCloudFiles(java.nio.file.Files.createTempDirectory("cloud-files"))
        val first=CloudStateStore(files).installationId
        assertEquals(first,CloudStateStore(files).installationId)
        files.write("installation-id","damaged")
        assertFails {CloudStateStore(files).installationId}
        assertEquals("damaged",files.read("installation-id"))
    }
    @Test fun failedPrivateCheckpointWriteKeepsThePreviousDurableValue() {
        val directory=java.nio.file.Files.createTempDirectory("cloud-write-failure")
        var fail=false
        val files=PrivateCloudFiles(directory,CloudFileProtection {_,isDirectory ->
            if(fail && !isDirectory)throw java.io.IOException("Injected storage failure")
        })
        files.write("checkpoint","acknowledged")
        fail=true
        assertFailsWith<java.io.IOException> {files.write("checkpoint","unacknowledged")}
        fail=false
        assertEquals("acknowledged",files.read("checkpoint"))
        assertFalse(files.names().any {it.startsWith(".cloud-")})
    }

}
