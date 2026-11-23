package pos.security

import java.io.*
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import pos.domain.*

/** Domain-separated public evidence; local signing secrets never enter business backups. */
object CashHandoverProof {
    private const val DOMAIN="pos.cash-handover.v1"
    fun encode(value:CashHandoverWitness):String = Base64.getEncoder().encodeToString(bytes {out ->writeUnsigned(out,value);out.writeUTF(value.signature)})
    internal fun unsignedBytes(value:CashHandoverWitness):ByteArray=bytes {writeUnsigned(it,value)}
    fun decode(value:String):CashHandoverWitness {
        require(value.length<=20_000)
        return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(value))).use {input ->
            require(input.readUTF()==DOMAIN)
            val id=input.readUTF()
            val request=CashHandoverRequest(input.readUTF(),input.readUTF(),input.readUTF(),input.readLong(),input.readLong(),input.text(),input.readUTF())
            val result=CashHandoverWitness(id,request,input.text(),input.readUTF(),StaffRole.valueOf(input.readUTF()),input.text(),input.readUTF(),StaffRole.valueOf(input.readUTF()),input.readUTF(),input.readUTF(),input.readUTF(),input.readUTF())
            require(input.read()==-1);verify(result);result
        }
    }
    fun verify(value:CashHandoverWitness) {
        val start=Instant.parse(value.acceptedAt);val end=Instant.parse(value.expiresAt)
        require(end>start && end<=start.plusSeconds(300)) {"Cash handover consent dates are invalid."}
        val key=Base64.getDecoder().decode(value.issuerPublicKey);val signature=Base64.getDecoder().decode(value.signature)
        require(key.size in 32..128 && signature.size==64)
        val verifier=Signature.getInstance("Ed25519")
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(key)))
        verifier.update(unsignedBytes(value));require(verifier.verify(signature)) {"Cash handover consent was changed."}
    }
    private fun writeUnsigned(out:DataOutputStream,value:CashHandoverWitness) {
        out.writeUTF(DOMAIN);out.writeUTF(value.id)
        with(value.request) {out.writeUTF(tenantId);out.writeUTF(operationId);out.writeUTF(shiftId);out.writeLong(expectedMinor);out.writeLong(countedMinor);out.text(countSheet);out.writeUTF(reason)}
        out.text(value.senderId);out.writeUTF(value.senderName);out.writeUTF(value.senderRole.name)
        out.text(value.receiverId);out.writeUTF(value.receiverName);out.writeUTF(value.receiverRole.name)
        out.writeUTF(value.acceptedAt);out.writeUTF(value.expiresAt);out.writeUTF(value.issuerPublicKey)
    }
    private fun DataOutputStream.text(value:String?) {writeBoolean(value!=null);value?.let(::writeUTF)}
    private fun DataInputStream.text():String?=if(readBoolean()) readUTF() else null
    private fun bytes(block:(DataOutputStream)->Unit):ByteArray=ByteArrayOutputStream().use {buffer ->DataOutputStream(buffer).use(block);buffer.toByteArray()}
}
