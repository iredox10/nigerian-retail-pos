package pos.security

import java.io.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import pos.domain.*

/** Public verification travels with historical business records. Signing keys stay on this computer. */
object MinimumPriceApprovalProof {
    fun encode(value: MinimumPriceApprovalWitness): String = Base64.getEncoder().encodeToString(bytes { out ->
        writeUnsigned(out, value)
        out.writeUTF(value.signature)
    })

    fun decode(value: String): MinimumPriceApprovalWitness {
        require(value.length <= 500_000) { "Price approval evidence is too large." }
        return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(value))).use { input ->
            require(input.readInt() == 1) { "Price approval evidence has an unsupported format." }
            val id = input.readUTF()
            val request = readRequest(input)
            val requester = input.nullableText()
            val requesterName = input.readUTF()
            val requesterRole = StaffRole.valueOf(input.readUTF())
            val approver = input.nullableText()
            val approverName = input.readUTF()
            val approverRole = StaffRole.valueOf(input.readUTF())
            val reason = input.readUTF()
            val at = input.readUTF()
            val expiry = input.readUTF()
            val publicKey = input.readUTF()
            val signature = input.readUTF()
            require(input.read() == -1) { "Price approval evidence contains extra data." }
            MinimumPriceApprovalWitness(id, request, requester, requesterName, requesterRole,
                approver, approverName, approverRole, reason, at, expiry, publicKey, signature).also(::verify)
        }
    }

    fun verify(value: MinimumPriceApprovalWitness) {
        val start = Instant.parse(value.approvedAt)
        val end = Instant.parse(value.expiresAt)
        require(end > start && end <= start.plusSeconds(300) && value.request.requiresApproval) { "Price approval dates or scope are invalid." }
        val keyBytes = Base64.getDecoder().decode(value.issuerPublicKey)
        val signature = Base64.getDecoder().decode(value.signature)
        require(keyBytes.size in 32..128 && signature.size == 64) { "Price approval signature is invalid." }
        val key = ApprovalCryptography.factory().generatePublic(X509EncodedKeySpec(keyBytes))
        val verifier = ApprovalCryptography.signature()
        verifier.initVerify(key)
        verifier.update(unsignedBytes(value))
        require(verifier.verify(signature)) { "Price approval evidence was changed or is invalid." }
    }

    internal fun unsignedBytes(value: MinimumPriceApprovalWitness): ByteArray = bytes { writeUnsigned(it, value) }
    private fun writeUnsigned(out: DataOutputStream, value: MinimumPriceApprovalWitness) {
        out.writeInt(1); out.writeUTF(value.id); writeRequest(out, value.request)
        out.nullableText(value.requesterId); out.writeUTF(value.requesterName); out.writeUTF(value.requesterRole.name)
        out.nullableText(value.approverId); out.writeUTF(value.approverName); out.writeUTF(value.approverRole.name)
        out.writeUTF(value.reason); out.writeUTF(value.approvedAt); out.writeUTF(value.expiresAt); out.writeUTF(value.issuerPublicKey)
    }
    private fun writeRequest(out: DataOutputStream, value: MinimumPriceApprovalRequest) {
        out.writeUTF(value.tenantId); out.writeUTF(value.operationId); out.writeUTF(value.fingerprint)
        out.writeLong(value.totalMinor); out.writeBoolean(value.hasDiscounts); out.writeInt(value.lines.size)
        value.lines.forEach { line ->
            out.writeUTF(line.key); out.writeUTF(line.productId); out.writeUTF(line.name)
            out.writeUTF(line.quantity); out.writeUTF(line.unit); out.writeLong(line.totalMinor)
        }
        out.writeInt(value.checks.size)
        value.checks.forEach { check ->
            out.writeUTF(check.lineKey); out.writeUTF(check.productId); out.writeUTF(check.unitKey)
            out.writeLong(check.policyVersion); out.writeLong(check.requiredMinor)
        }
    }
    private fun readRequest(input: DataInputStream): MinimumPriceApprovalRequest {
        val tenant = input.readUTF(); val operation = input.readUTF(); val fingerprint = input.readUTF()
        val total = input.readLong(); val discounts = input.readBoolean()
        val lineCount = input.readInt().also { require(it in 1..200) }
        val lines = List(lineCount) { MinimumPriceApprovalLine(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), input.readLong()) }
        val checksCount = input.readInt().also { require(it in 0..400) }
        val checks = List(checksCount) { MinimumPriceApprovalCheck(input.readUTF(), input.readUTF(), input.readUTF(), input.readLong(), input.readLong()) }
        return MinimumPriceApprovalRequest(tenant, operation, fingerprint, lines, checks, total, discounts)
    }
    private fun DataOutputStream.nullableText(value: String?) { writeBoolean(value != null); value?.let(::writeUTF) }
    private fun DataInputStream.nullableText(): String? = if (readBoolean()) readUTF() else null
    private fun bytes(block: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use(block); buffer.toByteArray()
    }
}

/** One signing identity per protected local security directory, created under an interprocess lock. */
internal class MinimumPriceApprovalSigner(directory: Path) {
    private val pair: KeyPair = loadOrCreate(directory.resolve("approval-signing"))
    val publicKey: String get() = Base64.getEncoder().encodeToString(pair.public.encoded)
    internal fun signPayload(payload:ByteArray):String {
        val signer=ApprovalCryptography.signature()
        signer.initSign(pair.private);signer.update(payload)
        return Base64.getEncoder().encodeToString(signer.sign())
    }
    fun sign(witness: MinimumPriceApprovalWitness): MinimumPriceApprovalWitness {
        require(witness.issuerPublicKey == publicKey)
        val signer = ApprovalCryptography.signature()
        signer.initSign(pair.private)
        signer.update(MinimumPriceApprovalProof.unsignedBytes(witness))
        return witness.copy(signature = Base64.getEncoder().encodeToString(signer.sign())).also(MinimumPriceApprovalProof::verify)
    }
    private fun loadOrCreate(directory: Path): KeyPair {
        Files.createDirectories(directory)
        restrict(directory, "rwx------")
        val lock = directory.resolve("signing.lock").toAbsolutePath().normalize()
        return synchronized(locks.computeIfAbsent(lock.toString()) { Any() }) {
            FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                restrict(lock, "rw-------")
                channel.lock().use {
                    val file = directory.resolve("minimum-price.key")
                    if (!Files.exists(file)) {
                        val created = ApprovalCryptography.generator().generateKeyPair()
                        val record = "1:${Base64.getEncoder().encodeToString(created.public.encoded)}:${Base64.getEncoder().encodeToString(created.private.encoded)}"
                        val temp = Files.createTempFile(directory, ".approval-key-", ".tmp")
                        try {
                            restrict(temp, "rw-------")
                            FileChannel.open(temp, StandardOpenOption.WRITE).use { output ->
                                val bytes = java.nio.ByteBuffer.wrap(record.toByteArray(Charsets.US_ASCII))
                                while (bytes.hasRemaining()) output.write(bytes)
                                output.force(true)
                            }
                            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE)
                        } finally { Files.deleteIfExists(temp) }
                    }
                    require(Files.isRegularFile(file) && Files.size(file) <= 1024) { "Local approval signing key is unavailable." }
                    val parts = String(Files.readAllBytes(file), Charsets.US_ASCII).split(':')
                    require(parts.size == 3 && parts[0] == "1") { "Local approval signing key is invalid." }
                    val factory = ApprovalCryptography.factory()
                    val privateBytes = Base64.getDecoder().decode(parts[2])
                    try {
                        KeyPair(factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(parts[1]))),
                            factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes)))
                    } finally { privateBytes.fill(0) }
                }
            }
        }
    }
    private fun restrict(path: Path, mode: String) {
        LocalFilePermissions.restrict(path, mode)
    }
    companion object { private val locks = ConcurrentHashMap<String, Any>() }
}
