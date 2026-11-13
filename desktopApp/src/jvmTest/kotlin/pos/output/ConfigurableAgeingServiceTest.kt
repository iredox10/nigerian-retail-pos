package pos.output

import kotlin.test.*
import pos.domain.*
import java.time.LocalDate

class ConfigurableAgeingServiceTest {
    private val day=LocalDate.parse("2026-11-13")
    private fun row(unassigned:Boolean=false):CreditReviewRow {
        val sales=listOf(0L,1L,15L,16L,45L,46L).mapIndexed {index,late ->
            val item=Product("item-$index","shop","Rice","bag",100,10.0)
            Sale("sale-$index","shop",listOf(CartLine(item,1)),100,0,100,"customer","2026-01-01T00:00:00Z",day.minusDays(late).toString())
        }
        val payments=if(unassigned) listOf(CustomerRepayment("payment","shop","customer",50,"2026-11-12T00:00:00Z")) else emptyList()
        return DebtAgeingService.build("shop",listOf(Customer("customer","shop","=Amina",if(unassigned) 550 else 600)),sales,payments,day).single()
    }

    @Test fun customBoundariesAreInclusiveAndReconcileToTheSavedDebt() {
        val schedule=AgeingSchedule.parse(" 15, 45 ")
        assertEquals(listOf(100L,200L,200L,100L,0L),ConfigurableAgeingService.amounts(row(),day,schedule))
        assertEquals(0,schedule.index(day.toString(),day))
        assertEquals(0,schedule.index(day.plusDays(1).toString(),day))
        assertEquals(schedule.labels.lastIndex,schedule.index(null,day))
    }

    @Test fun invalidBandDefinitionsRejectRatherThanSilentlySortOrRound() {
        listOf("","0","30,7","7,7","7.5,30","-1,7","36501","1,2,3,4,5,6,7,8,9","7,").forEach {text -> assertFails(text) {AgeingSchedule.parse(text)}}
        assertEquals(listOf(7,30,60,90),AgeingSchedule.DEFAULT.upperDays)
    }

    @Test fun unassignedPaymentsKeepEveryExactBandUnavailable() {
        assertNull(ConfigurableAgeingService.amounts(row(true),day,AgeingSchedule.DEFAULT))
        val csv=ConfigurableAgeingService.csv("shop",listOf(row(true)),day,AgeingSchedule.parse("15,45"),true)
        assertContains(csv,"\"false\",\"\",\"\",\"\",\"\",\"\"")
        assertContains(csv,"\"'=Amina\"")
        assertContains(csv,"Recorded history through")
    }

    @Test fun foreignDuplicateAndUnreconciledRowsReject() {
        val row=row()
        assertFails {ConfigurableAgeingService.csv("other",listOf(row),day,AgeingSchedule.DEFAULT,false)}
        assertFails {ConfigurableAgeingService.csv("shop",listOf(row,row),day,AgeingSchedule.DEFAULT,false)}
        assertFails {ConfigurableAgeingService.amounts(row.copy(customer=row.customer.copy(balanceMinor=1)),day,AgeingSchedule.DEFAULT)}
    }
}
