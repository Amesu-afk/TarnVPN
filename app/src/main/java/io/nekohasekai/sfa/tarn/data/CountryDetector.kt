package io.nekohasekai.sfa.tarn.data

/**
 * Works out a country from whatever the user's subscription happened to name the profile.
 * Nothing here is authoritative — a miss just means the row shows no flag and the map no pin.
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

    /**
     * Hosting hubs providers put in a profile name without the country — "TestMiami-Test" is
     * a US node, and no country name appears in it anywhere. Whole-word matching against the
     * normalized name keeps "Miamiville" out; deliberately left out are city names that are
     * also ordinary English words (Nice, Split, Reading), because a wrong flag is worse than
     * no flag. A city only ever resolves to its country: the map still pins the capital.
     */
    private val CITIES: Map<String, String> = mapOf(
        "new york" to "US", "nyc" to "US", "los angeles" to "US", "san francisco" to "US",
        "san diego" to "US", "san jose" to "US", "seattle" to "US", "chicago" to "US",
        "dallas" to "US", "houston" to "US", "austin" to "US", "miami" to "US",
        "tampa" to "US", "orlando" to "US", "atlanta" to "US", "ashburn" to "US",
        "washington" to "US", "boston" to "US", "philadelphia" to "US", "phoenix" to "US",
        "denver" to "US", "las vegas" to "US", "detroit" to "US", "minneapolis" to "US",
        "nashville" to "US", "charlotte" to "US", "raleigh" to "US", "honolulu" to "US",
        "anchorage" to "US", "salt lake city" to "US",
        "toronto" to "CA", "montreal" to "CA", "vancouver" to "CA", "calgary" to "CA",
        "ottawa" to "CA", "mexico city" to "MX", "guadalajara" to "MX", "monterrey" to "MX",
        "havana" to "CU", "santo domingo" to "DO", "san juan" to "PR",

        "sao paulo" to "BR", "são paulo" to "BR", "rio de janeiro" to "BR",
        "brasilia" to "BR", "buenos aires" to "AR", "santiago" to "CL", "bogota" to "CO",
        "lima" to "PE", "quito" to "EC", "montevideo" to "UY", "caracas" to "VE",

        "frankfurt" to "DE", "berlin" to "DE", "munich" to "DE", "nuremberg" to "DE",
        "nurnberg" to "DE", "nürnberg" to "DE", "dusseldorf" to "DE", "düsseldorf" to "DE",
        "hamburg" to "DE", "cologne" to "DE", "koln" to "DE", "köln" to "DE",
        "stuttgart" to "DE", "leipzig" to "DE", "dresden" to "DE",
        "amsterdam" to "NL", "rotterdam" to "NL", "eindhoven" to "NL",
        "london" to "GB", "manchester" to "GB", "edinburgh" to "GB", "glasgow" to "GB",
        "birmingham" to "GB", "leeds" to "GB", "liverpool" to "GB", "bristol" to "GB",
        "paris" to "FR", "marseille" to "FR", "lyon" to "FR", "toulouse" to "FR",
        "strasbourg" to "FR", "lille" to "FR", "bordeaux" to "FR",
        "madrid" to "ES", "barcelona" to "ES", "seville" to "ES",
        "lisbon" to "PT", "porto" to "PT", "rome" to "IT", "milan" to "IT",
        "florence" to "IT", "turin" to "IT", "vienna" to "AT", "salzburg" to "AT",
        "zurich" to "CH", "zürich" to "CH", "geneva" to "CH", "basel" to "CH",
        "brussels" to "BE", "antwerp" to "BE", "luxembourg" to "LU",
        "copenhagen" to "DK", "oslo" to "NO", "bergen" to "NO",
        "stockholm" to "SE", "gothenburg" to "SE", "malmo" to "SE", "malmö" to "SE",
        "helsinki" to "FI", "espoo" to "FI", "tampere" to "FI",
        "tallinn" to "EE", "tartu" to "EE", "riga" to "LV", "vilnius" to "LT",
        "kaunas" to "LT", "warsaw" to "PL", "krakow" to "PL", "wroclaw" to "PL",
        "gdansk" to "PL", "prague" to "CZ", "brno" to "CZ", "bratislava" to "SK",
        "budapest" to "HU", "bucharest" to "RO", "cluj" to "RO", "sofia" to "BG",
        "varna" to "BG", "belgrade" to "RS", "novi sad" to "RS", "zagreb" to "HR",
        "ljubljana" to "SI", "sarajevo" to "BA", "skopje" to "MK", "tirana" to "AL",
        "podgorica" to "ME", "athens" to "GR", "thessaloniki" to "GR",
        "nicosia" to "CY", "limassol" to "CY", "valletta" to "MT",
        "reykjavik" to "IS", "dublin" to "IE", "minsk" to "BY",
        "chisinau" to "MD", "kishinev" to "MD",
        "kyiv" to "UA", "kiev" to "UA", "lviv" to "UA", "odesa" to "UA",
        "odessa" to "UA", "kharkiv" to "UA",
        "moscow" to "RU", "moskva" to "RU", "saint petersburg" to "RU",
        "st petersburg" to "RU", "novosibirsk" to "RU", "yekaterinburg" to "RU",
        "istanbul" to "TR", "ankara" to "TR", "izmir" to "TR",
        "yerevan" to "AM", "tbilisi" to "GE", "batumi" to "GE", "baku" to "AZ",
        "almaty" to "KZ", "astana" to "KZ", "nur sultan" to "KZ",
        "tashkent" to "UZ", "samarkand" to "UZ", "bishkek" to "KG",
        "dushanbe" to "TJ", "ashgabat" to "TM", "ulaanbaatar" to "MN",

        "dubai" to "AE", "abu dhabi" to "AE", "riyadh" to "SA", "jeddah" to "SA",
        "dammam" to "SA", "doha" to "QA", "kuwait city" to "KW", "kuwait" to "KW",
        "manama" to "BH", "muscat" to "OM", "amman" to "JO", "beirut" to "LB",
        "baghdad" to "IQ", "erbil" to "IQ", "tehran" to "IR", "isfahan" to "IR",
        "tel aviv" to "IL", "jerusalem" to "IL", "haifa" to "IL",
        "tokyo" to "JP", "osaka" to "JP", "nagoya" to "JP", "yokohama" to "JP",
        "seoul" to "KR", "busan" to "KR", "incheon" to "KR",
        "taipei" to "TW", "kaohsiung" to "TW", "beijing" to "CN", "shanghai" to "CN",
        "shenzhen" to "CN", "guangzhou" to "CN", "chengdu" to "CN", "hangzhou" to "CN",
        "mumbai" to "IN", "delhi" to "IN", "new delhi" to "IN", "bangalore" to "IN",
        "bengaluru" to "IN", "chennai" to "IN", "kolkata" to "IN", "pune" to "IN",
        "jakarta" to "ID", "surabaya" to "ID", "bandung" to "ID",
        "kuala lumpur" to "MY", "bangkok" to "TH", "chiang mai" to "TH",
        "hanoi" to "VN", "ho chi minh" to "VN", "saigon" to "VN", "da nang" to "VN",
        "manila" to "PH", "cebu" to "PH", "karachi" to "PK", "lahore" to "PK",
        "islamabad" to "PK", "dhaka" to "BD", "chittagong" to "BD",
        "kathmandu" to "NP", "colombo" to "LK", "yangon" to "MM",
        "phnom penh" to "KH", "vientiane" to "LA", "kabul" to "AF",
        "macau" to "MO", "thimphu" to "BT",

        "sydney" to "AU", "melbourne" to "AU", "brisbane" to "AU", "perth" to "AU",
        "adelaide" to "AU", "canberra" to "AU", "auckland" to "NZ",
        "wellington" to "NZ", "suva" to "FJ",

        "johannesburg" to "ZA", "cape town" to "ZA", "durban" to "ZA",
        "pretoria" to "ZA", "cairo" to "EG", "alexandria" to "EG",
        "casablanca" to "MA", "rabat" to "MA", "marrakesh" to "MA",
        "algiers" to "DZ", "oran" to "DZ", "tunis" to "TN", "lagos" to "NG",
        "abuja" to "NG", "accra" to "GH", "nairobi" to "KE", "mombasa" to "KE",
        "addis ababa" to "ET", "dar es salaam" to "TZ", "zanzibar" to "TZ",
        "kampala" to "UG", "dakar" to "SN", "abidjan" to "CI", "yaounde" to "CM",
        "douala" to "CM", "luanda" to "AO", "maputo" to "MZ", "harare" to "ZW",
        "lusaka" to "ZM", "gaborone" to "BW", "windhoek" to "NA", "port louis" to "MU",
    )

    /** The two regional-indicator code points encode one ISO letter each: 🇩🇪 is `D` + `E`. */
    private const val REGIONAL_FIRST = 0x1F1E6
    private const val REGIONAL_LAST = 0x1F1FF

    /** ISO codes we accept from a leading `de-ber-01`-style tag. */
    private val ISO_CODES: Set<String> = NAMES.values.toSet() + setOf(
        "AL", "BA", "BY", "CY", "GR", "HR", "IS", "LU", "MK", "MT", "ME", "PT", "SI", "SK",
        "TH", "VN", "MY", "ID", "PH", "PK", "BD", "NP", "LK", "NZ", "EG", "MA", "NG", "KE",
        "SA", "QA", "KW", "JO", "IR", "IQ", "UZ", "KG", "TJ", "AZ",
    )

    /**
     * Tries a flag emoji first, then the human name, then a hosting city, then the outbound
     * tag, then a `xx-` prefix.
     *
     * Known limitation: "Georgia" is both a country and a US state, and Atlanta is a
     * common hosting location — a server named for the state gets the country's flag.
     * There is no signal in the name to tell them apart, so the country wins.
     */
    fun detect(displayName: String, tag: String?): String? {
        fromFlag(displayName)?.let { return it }
        fromNames(displayName)?.let { return it }
        fromCities(displayName)?.let { return it }
        tag?.let(::fromNames)?.let { return it }
        tag?.let(::fromCities)?.let { return it }

        tag?.let { fromTag(it, freeText = false) }?.let { return it }
        // The display name is prose, so only the leading token is trusted here — see [fromTag].
        return fromTag(displayName, freeText = true)
    }

    /**
     * Reads a neighboring pair of regional-indicator symbols — the flag emoji a provider
     * typically puts in front of each profile name ("🇩🇪 Germany"). [normalize] strips
     * everything that is not a letter or digit, so names like that used to reach the country
     * name table with no country in them at all. Only codes we already know are accepted, and
     * a stray pair that happens to look like one is no worse than the two-letter words the
     * name table refuses.
     */
    private fun fromFlag(raw: String): String? {
        var index = 0
        while (index < raw.length) {
            val first = raw.codePointAt(index)
            index += Character.charCount(first)
            if (first !in REGIONAL_FIRST..REGIONAL_LAST) continue
            if (index >= raw.length) return null
            val second = raw.codePointAt(index)
            if (second !in REGIONAL_FIRST..REGIONAL_LAST) continue
            index += Character.charCount(second)
            val code = buildString(2) {
                append('A' + (first - REGIONAL_FIRST))
                append('A' + (second - REGIONAL_FIRST))
            }
            if (code in ISO_CODES) return code
        }
        return null
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
     * Matches [CITIES] the same whole-word way as [fromNames]. A city is only evidence of
     * the country, which is why the map still pins the capital instead of inventing a city
     * coordinate for it.
     */
    private fun fromCities(raw: String): String? {
        val normalized = normalize(raw)
        return CITIES.entries.firstOrNull { normalized.contains(" ${it.key} ") }?.value
    }

    /**
     * Splits `TestMiami` into `Test Miami` and `NYCServer` into `NYC Server`; without this,
     * the most common provider pattern — a prefix glued to the city — leaves the city inside
     * one long word, where whole-word matching never sees it.
     */
    private val CAMEL_BOUNDARY = Regex(
        "(?<=[\\p{Ll}\\d])(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})",
    )

    /**
     * Lowercased, CamelCase split, punctuation flattened to spaces, runs of spaces collapsed,
     * and the whole thing space-padded — so `" key "` tests for a whole word, and a two-word
     * key still matches across whatever separated it ("Hong, Kong" and "Hong-Kong" alike).
     */
    private fun normalize(raw: String): String = raw
        .replace(CAMEL_BOUNDARY, " ")
        .lowercase()
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
