package com.myksoft.aganalizi

object CellUtils {

    data class BandInfo(val band: String, val frequency: String)

    fun getLteBandInfo(earfcn: Int?): BandInfo {
        if (earfcn == null) return BandInfo("N/A", "N/A")
        
        return when (earfcn) {
            in 0..599 -> BandInfo("B1", "2100 MHz")
            in 600..1199 -> BandInfo("B2", "1900 MHz")
            in 1200..1949 -> BandInfo("B3", "1800 MHz")
            in 1950..2399 -> BandInfo("B4", "1700/2100 MHz")
            in 2400..2649 -> BandInfo("B5", "850 MHz")
            in 2750..3449 -> BandInfo("B7", "2600 MHz")
            in 3450..3799 -> BandInfo("B8", "900 MHz")
            in 6150..6449 -> BandInfo("B20", "800 MHz")
            in 9210..9659 -> BandInfo("B28", "700 MHz")
            in 38650..39649 -> BandInfo("B40", "2300 MHz")
            in 39650..41589 -> BandInfo("B41", "2500 MHz")
            else -> {
                val freq = 2110.0 + (0.1 * earfcn)
                BandInfo("Band Unknown", String.format(java.util.Locale.US, "%.1f MHz", freq))
            }
        }
    }

    fun getNrBandInfo(nrarfcn: Int?): BandInfo {
        if (nrarfcn == null) return BandInfo("!", "!")
        
        return when (nrarfcn) {
            in 422000..434000 -> BandInfo("n1", "2100 MHz")
            in 361000..376000 -> BandInfo("n3", "1800 MHz")
            in 151600..160600 -> BandInfo("n28", "700 MHz")
            in 499200..537999 -> BandInfo("n41", "2500 MHz")
            in 620000..680000 -> BandInfo("n78", "3500 MHz")
            else -> {
                val freq = if (nrarfcn < 600000) nrarfcn * 0.005 else (nrarfcn - 600000) * 0.015 + 3000
                BandInfo("n??", String.format(java.util.Locale.US, "%.1f MHz", freq))
            }
        }
    }
}
