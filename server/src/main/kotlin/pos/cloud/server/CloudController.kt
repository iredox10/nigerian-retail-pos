package pos.cloud.server

import org.springframework.web.bind.annotation.*
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import pos.cloud.protocol.*

@RestController
@RequestMapping("/api/v1",produces=[MediaType.APPLICATION_JSON_VALUE])
class CloudController(private val accounts:CloudAccountService,private val businesses:CloudBusinessService,private val jdbc:JdbcTemplate,private val pairing:CloudPairingService,private val recovery:CloudEmailRecovery) {
    @GetMapping("/health") fun health():String {
        check(jdbc.queryForObject("SELECT 1",Int::class.java)==1)
        return "{\"status\":\"ok\",\"protocolVersion\":1}"
    }
    @PostMapping("/accounts/register",consumes=[MediaType.APPLICATION_JSON_VALUE]) @ResponseStatus(HttpStatus.CREATED)
    fun register(@RequestBody body:String)=CloudCodec.encode(accounts.register(CloudCodec.decode(body)))
    @PostMapping("/accounts/login",consumes=[MediaType.APPLICATION_JSON_VALUE])
    fun login(@RequestBody body:String)=CloudCodec.encode(accounts.login(CloudCodec.decode(body)))
    @GetMapping("/accounts/capabilities") fun capabilities()=CloudCodec.encode(recovery.capabilities())
    @PostMapping("/accounts/verification/send") fun sendVerification(@RequestHeader("Authorization",required=false) token:String?)=CloudCodec.encode(recovery.sendVerification(token))
    @PostMapping("/accounts/verification/confirm",consumes=[MediaType.APPLICATION_JSON_VALUE])
    fun verify(@RequestHeader("Authorization",required=false) token:String?,@RequestBody body:String)=CloudCodec.encode(recovery.verify(token,CloudCodec.decode(body)))
    @PostMapping("/accounts/password/request",consumes=[MediaType.APPLICATION_JSON_VALUE])
    fun requestReset(@RequestBody body:String,request:jakarta.servlet.http.HttpServletRequest)=CloudCodec.encode(recovery.requestReset(CloudCodec.decode(body),request.remoteAddr))
    @PostMapping("/accounts/password/reset",consumes=[MediaType.APPLICATION_JSON_VALUE])
    fun reset(@RequestBody body:String,request:jakarta.servlet.http.HttpServletRequest)=CloudCodec.encode(recovery.reset(CloudCodec.decode(body),request.remoteAddr))
    @PostMapping("/pairings",consumes=[MediaType.APPLICATION_JSON_VALUE])
    fun startPairing(@RequestBody body:String,request:jakarta.servlet.http.HttpServletRequest)=CloudCodec.encode(pairing.start(CloudCodec.decode(body),request.remoteAddr))
    @PostMapping("/pairings/claim",consumes=[MediaType.APPLICATION_JSON_VALUE]) fun claimPairing(@RequestBody body:String)=CloudCodec.encode(pairing.claim(CloudCodec.decode(body)))
    @PostMapping("/pairings/finish",consumes=[MediaType.APPLICATION_JSON_VALUE]) @ResponseStatus(HttpStatus.NO_CONTENT)
    fun finishPairing(@RequestBody body:String) {pairing.finish(CloudCodec.decode(body))}
    @PostMapping("/businesses/{id}/pairings/review",consumes=[MediaType.APPLICATION_JSON_VALUE])
    fun reviewPairing(@PathVariable id:String,@RequestHeader("Authorization",required=false) token:String?,@RequestBody body:String)=CloudCodec.encode(pairing.review(token,id,CloudCodec.decode(body)))
    @PostMapping("/businesses/{id}/pairings/approve",consumes=[MediaType.APPLICATION_JSON_VALUE]) @ResponseStatus(HttpStatus.NO_CONTENT)
    fun approvePairing(@PathVariable id:String,@RequestHeader("Authorization",required=false) token:String?,@RequestBody body:String) {pairing.approve(token,id,CloudCodec.decode(body))}
    @GetMapping("/businesses") fun list(@RequestHeader("Authorization",required=false) token:String?)=CloudCodec.encode(businesses.businesses(token))
    @PostMapping("/businesses",consumes=[MediaType.APPLICATION_JSON_VALUE]) @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestHeader("Authorization",required=false) token:String?,@RequestBody body:String):String {
        accounts.account(token)
        return CloudCodec.encode(businesses.create(token,CloudCodec.decode(body)))
    }
    @PostMapping("/businesses/{id}/devices",consumes=[MediaType.APPLICATION_JSON_VALUE])
    fun enroll(@PathVariable id:String,@RequestHeader("Authorization",required=false) token:String?,@RequestBody body:String):String {
        accounts.owner(accounts.account(token),id)
        return CloudCodec.encode(accounts.enroll(token,id,CloudCodec.decode(body)))
    }
    @GetMapping("/businesses/{id}/devices") fun devices(@PathVariable id:String,@RequestHeader("Authorization",required=false) token:String?)=CloudCodec.encode(accounts.devices(token,id))
    @GetMapping("/businesses/{id}/devices/details") fun deviceDetails(@PathVariable id:String,@RequestHeader("Authorization",required=false) token:String?)=CloudCodec.encode(accounts.deviceDetails(token,id))
    @DeleteMapping("/businesses/{id}/devices/{device}") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(@PathVariable id:String,@PathVariable device:String,@RequestHeader("Authorization",required=false) token:String?) {accounts.revoke(token,id,device)}
    @GetMapping("/businesses/{id}/bootstrap") fun bootstrap(@PathVariable id:String,@RequestHeader("Authorization",required=false) token:String?)=CloudCodec.encode(businesses.bootstrap(token,id)).also {accounts.touchDevice(token,id)}
    @GetMapping("/businesses/{id}/sync/pull") fun pull(@PathVariable id:String,@RequestParam after:Long,@RequestParam(defaultValue="50") limit:Int,@RequestHeader("Authorization",required=false) token:String?)=CloudCodec.encode(businesses.pull(token,id,after,limit)).also {accounts.touchDevice(token,id)}
    @PostMapping("/businesses/{id}/sync/push",consumes=[MediaType.APPLICATION_JSON_VALUE])
    fun push(@PathVariable id:String,@RequestHeader("Authorization",required=false) token:String?,@RequestBody body:String):String {
        accounts.device(token,id)
        return CloudCodec.encode(businesses.push(token,id,CloudCodec.decode(body))).also {accounts.touchDevice(token,id)}
    }
}
