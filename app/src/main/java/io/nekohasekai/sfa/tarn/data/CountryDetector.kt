package io.nekohasekai.sfa.tarn.data

/**
 * Works out a country from whatever the user's subscription happened to name the profile.
 * Nothing here is authoritative — a miss just means the row shows no flag.
 */
object CountryDetector {
    /**
     * Russian and English names we are likely to meet in a vless:// fragment.
     * Keys are lowercase and matched as substrings, so "Германия, Берлин" hits "германия".
     */
    private val NAMES: Map<String, String> = mapOf(
        "германия" to "DE", "germany" to "DE", "deutschland" to "DE",
        "нидерланды" to "NL", "голландия" to "NL", "netherlands" to "NL", "holland" to "NL",
        "сша" to "US", "америка" to "US", "united states" to "US", "usa" to "US",
        "великобритания" to "GB", "англия" to "GB", "britain" to "GB", "united kingdom" to "GB",
        "япония" to "JP", "japan" to "JP",
        "сингапур" to "SG", "singapore" to "SG",
        "франция" to "FR", "france" to "FR",
        "канада" to "CA", "canada" to "CA",
        "финляндия" to "FI", "finland" to "FI",
        "швеция" to "SE", "sweden" to "SE",
        "швейцария" to "CH", "switzerland" to "CH",
        "польша" to "PL", "poland" to "PL",
        "турция" to "TR", "turkey" to "TR", "türkiye" to "TR",
        "россия" to "RU", "russia" to "RU",
        "украина" to "UA", "ukraine" to "UA",
        "казахстан" to "KZ", "kazakhstan" to "KZ",
        "латвия" to "LV", "latvia" to "LV",
        "литва" to "LT", "lithuania" to "LT",
        "эстония" to "EE", "estonia" to "EE",
        "австрия" to "AT", "austria" to "AT",
        "испания" to "ES", "spain" to "ES",
        "италия" to "IT", "italy" to "IT",
        "норвегия" to "NO", "norway" to "NO",
        "дания" to "DK", "denmark" to "DK",
        "чехия" to "CZ", "czech" to "CZ",
        "румыния" to "RO", "romania" to "RO",
        "болгария" to "BG", "bulgaria" to "BG",
        "молдова" to "MD", "moldova" to "MD",
        "армения" to "AM", "armenia" to "AM",
        "грузия" to "GE", "georgia" to "GE",
        "гонконг" to "HK", "hong kong" to "HK",
        "корея" to "KR", "korea" to "KR",
        "китай" to "CN", "china" to "CN",
        "тайвань" to "TW", "taiwan" to "TW",
        "индия" to "IN", "india" to "IN",
        "австралия" to "AU", "australia" to "AU",
        "бразилия" to "BR", "brazil" to "BR",
        "оаэ" to "AE", "эмираты" to "AE", "emirates" to "AE", "dubai" to "AE",
        "израиль" to "IL", "israel" to "IL",
        "ирландия" to "IE", "ireland" to "IE",
        "бельгия" to "BE", "belgium" to "BE",
        "венгрия" to "HU", "hungary" to "HU",
        "сербия" to "RS", "serbia" to "RS",
        "мексика" to "MX", "mexico" to "MX",
        "аргентина" to "AR", "argentina" to "AR",
        "чили" to "CL", "chile" to "CL",
        "юар" to "ZA", "south africa" to "ZA",
    )

    /** ISO codes we accept from a leading `de-ber-01`-style tag. */
    private val ISO_CODES: Set<String> = NAMES.values.toSet() + setOf(
        "AL", "BA", "BY", "CY", "GR", "HR", "IS", "LU", "MK", "MT", "ME", "PT", "SI", "SK",
        "TH", "VN", "MY", "ID", "PH", "PK", "BD", "NP", "LK", "NZ", "EG", "MA", "NG", "KE",
        "SA", "QA", "KW", "JO", "IR", "IQ", "UZ", "KG", "TJ", "AZ",
    )

    /**
     * Tries the human name first, then the outbound tag, then a `xx-` prefix.
     *
     * Known limitation: "Georgia" is both a country and a US state, and Atlanta is a
     * common hosting location — a server named for the state gets the country's flag.
     * There is no signal in the name to tell them apart, so the country wins.
     */
    fun detect(displayName: String, tag: String?): String? {
        fromNames(displayName)?.let { return it }
        tag?.let(::fromNames)?.let { return it }

        tag?.let { fromTag(it, freeText = false) }?.let { return it }
        // The display name is prose, so only the leading token is trusted here — see [fromTag].
        return fromTag(displayName, freeText = true)
    }

    /**
     * Matches [NAMES] on whole words rather than raw substrings, so a country name has to
     * stand on its own instead of turning up inside a longer word.
     */
    private fun fromNames(raw: String): String? {
        val normalized = normalize(raw)
        return NAMES.entries.firstOrNull { normalized.contains(" ${it.key} ") }?.value
    }

    /**
     * Lowercased, punctuation flattened to spaces, runs of spaces collapsed, and the whole
     * thing space-padded — so `" key "` tests for a whole word, and a two-word key still
     * matches across whatever separated it ("Hong, Kong" and "Hong-Kong" alike).
     */
    private fun normalize(raw: String): String =
        raw.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(separator = " ", prefix = " ", postfix = " ")

    /**
     * Reads a country code out of a structured tag like `de-ber-01` or `vpn-us-nyc`.
     *
     * [freeText] is the important knob. A machine-made tag is a safe place to look for a
     * standalone two-letter code in any position. A human display name is not: it used to
     * be scanned the same way, and every short English word that happens to be an ISO code
     * became a country — "Server in Berlin" flew India's flag off `in`, "My Server" flew
     * Malaysia's off `my`.
     *
     * So in prose only the *leading* token is read, and only when it is written in capitals.
     * That is how a code is actually written when someone means one ("DE Berlin", "NL 01"),
     * while the words that caused the false flags are not ("My", "In", "It"). Case cannot be
     * used this way on a tag, which is conventionally all-lowercase, hence the split.
     */
    private fun fromTag(raw: String, freeText: Boolean): String? {
        val tokens = raw.split('-', '_', '.', ' ', ',', '/').filter { it.isNotBlank() }
        // A leading two-letter token is the common convention: de-ber-01, us-nyc-01.
        tokens.firstOrNull()?.let { head ->
            if (head.length == 2 && (!freeText || head.all(Char::isUpperCase))) {
                val code = head.uppercase()
                if (code in ISO_CODES) return code
            }
        }
        if (freeText) return null
        return tokens.asSequence()
            .filter { it.length == 2 }
            .map { it.uppercase() }
            .firstOrNull { it in ISO_CODES }
    }
}
