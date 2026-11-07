package shoplens

import kotlinx.serialization.Serializable

/**
 * TheRealReal consignor commission (the % of net sale price the seller keeps).
 *
 * Source: third-party copy of TRR's Earnings Guide, checked 2026-10-16
 * (https://www.underpriced.app/blog/the-realreal-consignment-guide-2026). TRR
 * changes these and runs promos, so treat this as config, not truth: verify
 * against https://www.therealreal.com/seller/commissions before relying on it.
 */
enum class CommissionClass { CLOTHING_SHOES_ACCESSORIES, HANDBAGS, FINE_JEWELRY, WATCHES, MENS_SNEAKERS }

object TheRealRealCommission {
    // Lower bound of each sale-price tier (USD), paired with keep% per class:
    // clothing/shoes/accessories, handbags, fine jewelry, watches, men's sneakers.
    private val tiers: List<Pair<Int, IntArray>> = listOf(
        0 to intArrayOf(20, 20, 20, 20, 20),
        100 to intArrayOf(30, 30, 30, 30, 30),
        150 to intArrayOf(45, 45, 45, 45, 45),
        200 to intArrayOf(55, 55, 55, 55, 55),
        300 to intArrayOf(60, 60, 65, 60, 65),
        500 to intArrayOf(60, 60, 65, 70, 75),
        750 to intArrayOf(65, 65, 70, 70, 75),
        1000 to intArrayOf(65, 65, 70, 70, 85),
        1500 to intArrayOf(65, 70, 70, 70, 85),
        2000 to intArrayOf(65, 70, 70, 75, 85),
        5000 to intArrayOf(70, 75, 70, 80, 85),
        7500 to intArrayOf(70, 80, 70, 85, 85),
    )

    fun keepPercent(salePrice: Int, cls: CommissionClass): Int =
        tiers.last { salePrice >= it.first }.second[cls.ordinal]

    fun classFor(category: String, subcategory: String?): CommissionClass {
        val c = category.lowercase()
        val s = subcategory.orEmpty().lowercase()
        return when {
            c.contains("watch") -> CommissionClass.WATCHES
            c.contains("jewel") -> CommissionClass.FINE_JEWELRY
            c.contains("handbag") || c == "bags" || c.contains("bag") -> CommissionClass.HANDBAGS
            (c.contains("shoe") || c.contains("sneaker")) && s.contains("sneaker") && s.contains("men") && !s.contains("women") ->
                CommissionClass.MENS_SNEAKERS
            else -> CommissionClass.CLOTHING_SHOES_ACCESSORIES
        }
    }
}

@Serializable
data class PayoutPoint(val salePrice: Int, val keepPercent: Int, val payout: Int)

@Serializable
data class PayoutEstimate(
    val platform: String,
    val commissionClass: String,
    val low: PayoutPoint,
    val typical: PayoutPoint,
    val high: PayoutPoint,
    val note: String,
)

object Payout {
    fun theRealReal(category: String, subcategory: String?, low: Int, typical: Int, high: Int): PayoutEstimate {
        val cls = TheRealRealCommission.classFor(category, subcategory)
        fun point(p: Int): PayoutPoint {
            val keep = TheRealRealCommission.keepPercent(p, cls)
            return PayoutPoint(p, keep, p * keep / 100)
        }
        return PayoutEstimate(
            platform = "TheRealReal (consignment)",
            commissionClass = cls.name,
            low = point(low), typical = point(typical), high = point(high),
            note = "Base rates only. Loyalty tiers add 1-5 pts on items ≥\$200; taking site credit adds 5%. Rates as of Oct 2026.",
        )
    }
}
