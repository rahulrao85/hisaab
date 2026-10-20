package com.govu.hisaab.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class ParserAndRulesTest {

    private val today = LocalDate.of(2026, 10, 20)
    private val now = today.atTime(9, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
    private val demo = Ingest.demo(File("src/main/assets/demo_sms.tsv").readText(), today)
    private val all = Ingest.ingestAll(demo, now = now)

    private fun p(body: String, sender: String = "AX-HDFCBK-S") = SmsParser.parse(1, now, sender, body)
    private fun byBody(fragment: String) = all.first { fragment in it.body }
    private fun types(fragment: String) = byBody(fragment).alerts.map { it.type }.toSet()

    @Test fun hdfcUpi() {
        val t = p("Sent Rs.342.00 From HDFC Bank A/C *4821 To SWIGGY On 08/10/26 Ref 629310000001 Not You? Call 18002586161")!!
        assertEquals(Kind.DEBIT, t.kind); assertEquals(342.0, t.amount, 0.0)
        assertEquals("SWIGGY", t.merchantRaw); assertEquals("4821", t.account)
        assertEquals("629310000001", t.ref); assertEquals("HDFC", t.bank)
    }

    @Test fun iciciDebitWithCreditedPayee() {
        val t = p("ICICI Bank Acct XX107 debited for Rs 1,199.00 on 08-Oct-26; AIRTEL credited. UPI:629310000002.", "JD-ICICIT-S")!!
        assertEquals(Kind.DEBIT, t.kind); assertEquals(1199.0, t.amount, 0.0); assertEquals("AIRTEL", t.merchantRaw)
        assertEquals(Channel.UPI, t.channel)
    }

    @Test fun axisP2m() {
        val t = p("INR 2,450.00 debited A/c no. XX9930 17-10-26, 19:02:15 UPI/P2M/629310000011/ZOMATO LTD Not you? SMS BLOCKUPI", "VM-AXISBK-S")!!
        assertEquals(2450.0, t.amount, 0.0); assertEquals("ZOMATO LTD", t.merchantRaw); assertEquals("ZOMATO", t.merchantKey)
    }

    @Test fun sbiBareAmount() {
        val t = p("Dear UPI user A/C X6612 debited by 4999.0 on date 19Oct26 trf to RAZ DREAMPLUG Refno 629310000014. If not u? call 1800111109. -SBI", "JD-SBIUPI-S")!!
        assertEquals(4999.0, t.amount, 0.0); assertEquals("RAZ DREAMPLUG", t.merchantRaw); assertEquals("SBI", t.bank)
    }

    @Test fun foreignCard() {
        val t = p("Your HSBC Credit Card ending 7731 has been used for USD 20.00 at OPENAI *CHATGPT SUBSCR on 19 Oct 2026 at 02:14. Avl limit INR 1,82,980.00", "VM-HSBCIN-S")!!
        assertEquals("USD", t.currency); assertEquals(20.0, t.amount, 0.0); assertEquals("OPENAI *CHATGPT SUBSCR", t.merchantRaw)
        assertEquals(Channel.CARD, t.channel); assertEquals("7731", t.account)
    }

    @Test fun salaryCreditNamesTheSender() {
        val t = p("INR 4,35,000.00 credited to A/c XX2210 on 10-10-26 by NEFT from ACME SOFTWARE PVT LTD. Avl Bal INR 6,12,450.00 -HSBC", "VM-HSBCIN-S")!!
        assertEquals(Kind.CREDIT, t.kind); assertEquals(435000.0, t.amount, 0.0); assertEquals("ACME SOFTWARE PVT LTD", t.merchantRaw)
    }

    @Test fun postpaidBillIsABillNotADebit() {
        val t = p("Dear Customer, your Airtel Postpaid bill of Rs.1,199.00 for 98XXXXXX10 is due on 25-Oct-2026.", "JD-ICICIT-S")!!
        assertEquals(Kind.BILL, t.kind); assertEquals(1199.0, t.amount, 0.0); assertEquals("Airtel", t.merchantRaw)
        assertEquals(LocalDate.of(2026, 10, 25), Summary.localDate(t.dueTs!!))
    }

    @Test fun cardStatementTakesTotalNotMinimum() {
        val t = p("HDFC Bank Credit Card XX5508 Statement: Total due Rs.18,450.00, Min due Rs.920.00, Due date 05-Nov-26.")!!
        assertEquals(Kind.BILL, t.kind); assertEquals(18450.0, t.amount, 0.0)
        assertEquals(LocalDate.of(2026, 11, 5), Summary.localDate(t.dueTs!!))
    }

    @Test fun noiseIsIgnored() {
        assertNull(p("Your OTP for txn of Rs.2,499.00 at AMAZON is 482910. Do not share it with anyone."))
        assertNull(p("Get a pre-approved personal loan up to Rs 5,00,000 at 10.5%. Apply now on the Kotak app!"))
        assertNull(p("Payment of Rs.1,499.00 has been received for your Airtel number 98XXXXXX10. Thank you!"))
    }

    @Test fun mandateIsUpcoming() {
        val t = p("Your e-mandate for YOUTUBEGOOGLE of Rs.299.00 will be debited on 25-Oct-2026 from HSBC Credit Card 7731")!!
        assertEquals(Kind.UPCOMING, t.kind); assertEquals("YOUTUBEGOOGLE", t.merchantRaw)
    }

    @Test fun demoRaisesTheRightAlerts() {
        assertTrue(AlertType.BILL_MISMATCH in types("Rs 1,499.00 on 17-Oct-26"))
        assertTrue(AlertType.SPIKE in types("INR 2,450.00"))
        assertTrue(AlertType.DUPLICATE in types("629310000013"))
        assertTrue(AlertType.NEW_MERCHANT in types("RAZ DREAMPLUG"))
        assertEquals(setOf(AlertType.FOREIGN, AlertType.ODD_HOUR), types("OPENAI"))
        assertTrue(AlertType.BILL_JUMP in types("Rs 3,310.00"))
        assertTrue(AlertType.NEW_AUTOPAY in types("YOUTUBEGOOGLE"))
    }

    @Test fun demoStaysQuietOnNormalSpends() {
        listOf("629310000003", "629310000005", "629310000015", "NETFLIX of Rs.649.00", "blinkit", "DMART").forEach {
            assertTrue("unexpected alert on $it: ${types(it)}", types(it).isEmpty())
        }
    }

    @Test fun airtelBillGetsMarkedPaid() {
        val bill = byBody("Airtel Postpaid bill")
        assertNotNull(bill.billPaidBy)
    }

    @Test fun firstScanDoesNotNagAboutOldSpends() {
        val back = Ingest.ingestAll(demo, now = now, backfill = true)
        val flipkart = back.first { "FLIPKART" in it.body }
        assertTrue(flipkart.alerts.isNotEmpty()); assertEquals(Verdict.MINE, flipkart.verdict)
        assertEquals(Verdict.NONE, back.first { "RAZ DREAMPLUG" in it.body }.verdict)
    }

    @Test fun summaryGuardRejectsInventedNumbers() {
        val f = Summary.facts(all, today.minusDays(1), now)
        assertTrue(Summary.numbersAreGrounded(Summary.template(f), f))
        assertTrue(!Summary.numbersAreGrounded("You spent ₹9,999 yesterday.", f))
    }

    @Test fun indianGrouping() {
        assertEquals("₹4,35,000", rupees(435000.0)); assertEquals("₹1,199", rupees(1199.0)); assertEquals("₹20.50", rupees(20.5))
    }
}
