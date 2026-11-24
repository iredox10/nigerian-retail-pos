package pos.cloud.server

import org.springframework.web.bind.annotation.*
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import pos.cloud.protocol.*

@RestController
@RequestMapping("/api/v1",produces=[MediaType.APPLICATION_JSON_VALUE])
class CloudController(private val accounts:CloudAccountService,private val businesses:CloudBusinessService,private val jdbc:JdbcTemplate) {
    @GetMapping("/health") fun health():String {
        check(jdbc.queryForObject("SELECT 1",Int::class.java)==1)
        return "{\"status\":\"ok\",\"protocolVersion\":1}"
    }
    @PostMapping("/accounts/register",consumes=[MediaType.APPLICATION_JSON_VALUE]) @ResponseStatus(HttpStatus.CREATED)
    fun register(@RequestBody body:String)=CloudCodec.encode(accounts.register(CloudCodec.decode(body)))
    @PostMapping("/accounts/login",consumes=[MediaType.APPLICATION_JSON_VALUE])
    fun login(@RequestBody body:String)=CloudCodec.encode(accounts.login(CloudCodec.decode(body)))
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
    @DeleteMapping("/businesses/{id}/devices/{device}") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(@PathVariable id:String,@PathVariable device:String,@RequestHeader("Authorization",required=false) token:String?) {accounts.revoke(token,id,device)}
    @GetMapping("/businesses/{id}/bootstrap") fun bootstrap(@PathVariable id:String,@RequestHeader("Authorization",required=false) token:String?)=CloudCodec.encode(businesses.bootstrap(token,id))
    @GetMapping("/businesses/{id}/sync/pull") fun pull(@PathVariable id:String,@RequestParam after:Long,@RequestParam(defaultValue="50") limit:Int,@RequestHeader("Authorization",required=false) token:String?)=CloudCodec.encode(businesses.pull(token,id,after,limit))
    @PostMapping("/businesses/{id}/sync/push",consumes=[MediaType.APPLICATION_JSON_VALUE])
    fun push(@PathVariable id:String,@RequestHeader("Authorization",required=false) token:String?,@RequestBody body:String):String {
        accounts.device(token,id)
        return CloudCodec.encode(businesses.push(token,id,CloudCodec.decode(body)))
    }
}
