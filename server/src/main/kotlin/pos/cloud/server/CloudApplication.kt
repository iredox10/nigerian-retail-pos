package pos.cloud.server

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import com.zaxxer.hikari.HikariDataSource
import java.net.URI
import java.time.Clock
import javax.sql.DataSource

@SpringBootApplication(proxyBeanMethods=false)
class CloudApplication {
    @Bean fun clock():Clock=Clock.systemUTC()
    @Bean fun dataSource(environment:Environment):DataSource {
        val source=HikariDataSource().apply {maximumPoolSize=5;minimumIdle=0;connectionTimeout=10_000;validationTimeout=5_000;poolName="retailpos-cloud"}
        val heroku=environment.getProperty("DATABASE_URL")
        if(!heroku.isNullOrBlank()) {
            val uri=URI(heroku)
            require(uri.scheme in setOf("postgres","postgresql") && !uri.host.isNullOrBlank()) {"Configure a PostgreSQL DATABASE_URL."}
            val credentials=requireNotNull(uri.rawUserInfo) {"DATABASE_URL requires credentials."}.split(':',limit=2)
            require(credentials.size==2)
            fun decode(s:String)=java.net.URLDecoder.decode(s.replace("+","%2B"),Charsets.UTF_8)
            source.jdbcUrl="jdbc:postgresql://${uri.host}:${if(uri.port==-1)5432 else uri.port}${uri.rawPath}?sslmode=require"
            source.username=decode(credentials[0]);source.password=decode(credentials[1])
        } else {
            val url=requireNotNull(environment.getProperty("CLOUD_JDBC_URL")) {"Set DATABASE_URL or CLOUD_JDBC_URL for persistent PostgreSQL storage."}
            require(url.startsWith("jdbc:postgresql:")) {"Cloud storage requires PostgreSQL."}
            source.jdbcUrl=url;source.username=requireNotNull(environment.getProperty("CLOUD_DB_USER"));source.password=requireNotNull(environment.getProperty("CLOUD_DB_PASSWORD"))
        }
        source.driverClassName="org.postgresql.Driver"
        return source
    }
}
fun main(args:Array<String>) {SpringApplication.run(CloudApplication::class.java,*args)}
