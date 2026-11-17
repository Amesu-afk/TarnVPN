package io.nekohasekai.sfa.tarn.data

/** A point on the home screen's map, in degrees: north and east are positive. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * Where the map drops its pin for a country.
 *
 * A profile only carries a country, never a city — the subscription names it "Германия,
 * Берлин" or the tag says `de-ber-01`, and neither is reliable enough to place a point by
 * hand. The capital is the one coordinate per country that is unambiguous, is where most
 * providers actually put a node, and stays right when a provider renames a server.
 *
 * A code without an entry here is not an error: the map still draws, just without a pin, and
 * a wrong pin would be worse than none.
 */
object CountryGeo {
    fun markerFor(code: String?): GeoPoint? = code?.trim()?.uppercase()?.let(CAPITALS::get)

    private val CAPITALS: Map<String, GeoPoint> = mapOf(
        // Europe
        "AL" to GeoPoint(41.33, 19.82), // Tirana
        "AT" to GeoPoint(48.21, 16.37), // Vienna
        "BA" to GeoPoint(43.86, 18.41), // Sarajevo
        "BE" to GeoPoint(50.85, 4.35), // Brussels
        "BG" to GeoPoint(42.70, 23.32), // Sofia
        "BY" to GeoPoint(53.90, 27.57), // Minsk
        "CH" to GeoPoint(46.95, 7.45), // Bern
        "CY" to GeoPoint(35.17, 33.36), // Nicosia
        "CZ" to GeoPoint(50.08, 14.44), // Prague
        "DE" to GeoPoint(52.52, 13.40), // Berlin
        "DK" to GeoPoint(55.68, 12.57), // Copenhagen
        "EE" to GeoPoint(59.44, 24.75), // Tallinn
        "ES" to GeoPoint(40.42, -3.70), // Madrid
        "FI" to GeoPoint(60.17, 24.94), // Helsinki
        "FR" to GeoPoint(48.86, 2.35), // Paris
        "GB" to GeoPoint(51.51, -0.13), // London
        "GR" to GeoPoint(37.98, 23.73), // Athens
        "HR" to GeoPoint(45.81, 15.98), // Zagreb
        "HU" to GeoPoint(47.50, 19.04), // Budapest
        "IE" to GeoPoint(53.35, -6.26), // Dublin
        "IS" to GeoPoint(64.15, -21.94), // Reykjavík
        "IT" to GeoPoint(41.90, 12.50), // Rome
        "LT" to GeoPoint(54.69, 25.28), // Vilnius
        "LU" to GeoPoint(49.61, 6.13), // Luxembourg
        "LV" to GeoPoint(56.95, 24.11), // Riga
        "MD" to GeoPoint(47.01, 28.86), // Chișinău
        "ME" to GeoPoint(42.44, 19.26), // Podgorica
        "MK" to GeoPoint(42.00, 21.43), // Skopje
        "MT" to GeoPoint(35.90, 14.51), // Valletta
        "NL" to GeoPoint(52.37, 4.90), // Amsterdam
        "NO" to GeoPoint(59.91, 10.75), // Oslo
        "PL" to GeoPoint(52.23, 21.01), // Warsaw
        "PT" to GeoPoint(38.72, -9.14), // Lisbon
        "RO" to GeoPoint(44.43, 26.10), // Bucharest
        "RS" to GeoPoint(44.79, 20.45), // Belgrade
        "RU" to GeoPoint(55.76, 37.62), // Moscow
        "SE" to GeoPoint(59.33, 18.07), // Stockholm
        "SI" to GeoPoint(46.06, 14.51), // Ljubljana
        "SK" to GeoPoint(48.15, 17.11), // Bratislava
        "UA" to GeoPoint(50.45, 30.52), // Kyiv
        "XK" to GeoPoint(42.66, 21.17), // Pristina

        // Middle East and Central Asia
        "AE" to GeoPoint(24.45, 54.38), // Abu Dhabi
        "AM" to GeoPoint(40.18, 44.51), // Yerevan
        "AZ" to GeoPoint(40.41, 49.87), // Baku
        "BH" to GeoPoint(26.23, 50.59), // Manama
        "GE" to GeoPoint(41.72, 44.79), // Tbilisi
        "IL" to GeoPoint(31.77, 35.21), // Jerusalem
        "IQ" to GeoPoint(33.31, 44.36), // Baghdad
        "IR" to GeoPoint(35.69, 51.39), // Tehran
        "JO" to GeoPoint(31.95, 35.93), // Amman
        "KG" to GeoPoint(42.87, 74.59), // Bishkek
        "KW" to GeoPoint(29.38, 47.99), // Kuwait City
        "KZ" to GeoPoint(51.17, 71.45), // Astana
        "LB" to GeoPoint(33.89, 35.50), // Beirut
        "OM" to GeoPoint(23.59, 58.38), // Muscat
        "QA" to GeoPoint(25.29, 51.53), // Doha
        "SA" to GeoPoint(24.71, 46.68), // Riyadh
        "SY" to GeoPoint(33.51, 36.29), // Damascus
        "TJ" to GeoPoint(38.56, 68.79), // Dushanbe
        "TM" to GeoPoint(37.96, 58.33), // Ashgabat
        "TR" to GeoPoint(39.93, 32.86), // Ankara
        "UZ" to GeoPoint(41.30, 69.24), // Tashkent
        "YE" to GeoPoint(15.37, 44.19), // Sanaa

        // Asia and Oceania
        "AF" to GeoPoint(34.56, 69.21), // Kabul
        "AU" to GeoPoint(-35.28, 149.13), // Canberra
        "BD" to GeoPoint(23.81, 90.41), // Dhaka
        "BN" to GeoPoint(4.94, 114.95), // Bandar Seri Begawan
        "BT" to GeoPoint(27.47, 89.64), // Thimphu
        "CN" to GeoPoint(39.90, 116.40), // Beijing
        "FJ" to GeoPoint(-18.14, 178.44), // Suva
        "HK" to GeoPoint(22.32, 114.17), // Hong Kong
        "ID" to GeoPoint(-6.21, 106.85), // Jakarta
        "IN" to GeoPoint(28.61, 77.21), // New Delhi
        "JP" to GeoPoint(35.68, 139.69), // Tokyo
        "KH" to GeoPoint(11.56, 104.92), // Phnom Penh
        "KR" to GeoPoint(37.57, 126.98), // Seoul
        "LA" to GeoPoint(17.97, 102.63), // Vientiane
        "LK" to GeoPoint(6.93, 79.86), // Colombo
        "MM" to GeoPoint(16.87, 96.20), // Yangon
        "MN" to GeoPoint(47.89, 106.91), // Ulaanbaatar
        "MO" to GeoPoint(22.20, 113.54), // Macau
        "MV" to GeoPoint(4.18, 73.51), // Malé
        "MY" to GeoPoint(3.14, 101.69), // Kuala Lumpur
        "NP" to GeoPoint(27.72, 85.32), // Kathmandu
        "NZ" to GeoPoint(-41.29, 174.78), // Wellington
        "PH" to GeoPoint(14.60, 120.98), // Manila
        "PK" to GeoPoint(33.68, 73.05), // Islamabad
        "SG" to GeoPoint(1.35, 103.82), // Singapore
        "TH" to GeoPoint(13.76, 100.50), // Bangkok
        "TW" to GeoPoint(25.03, 121.57), // Taipei
        "VN" to GeoPoint(21.03, 105.85), // Hanoi

        // Africa
        "AO" to GeoPoint(-8.84, 13.23), // Luanda
        "BW" to GeoPoint(-24.65, 25.91), // Gaborone
        "CI" to GeoPoint(5.36, -4.01), // Abidjan
        "CM" to GeoPoint(3.85, 11.50), // Yaoundé
        "DZ" to GeoPoint(36.75, 3.06), // Algiers
        "EG" to GeoPoint(30.04, 31.24), // Cairo
        "ET" to GeoPoint(9.03, 38.74), // Addis Ababa
        "GH" to GeoPoint(5.60, -0.19), // Accra
        "KE" to GeoPoint(-1.29, 36.82), // Nairobi
        "MA" to GeoPoint(34.02, -6.84), // Rabat
        "MU" to GeoPoint(-20.16, 57.50), // Port Louis
        "MZ" to GeoPoint(-25.97, 32.57), // Maputo
        "NA" to GeoPoint(-22.56, 17.08), // Windhoek
        "NG" to GeoPoint(9.06, 7.49), // Abuja
        "SN" to GeoPoint(14.72, -17.47), // Dakar
        "TN" to GeoPoint(36.81, 10.18), // Tunis
        "TZ" to GeoPoint(-6.79, 39.21), // Dar es Salaam
        "UG" to GeoPoint(0.35, 32.58), // Kampala
        "ZA" to GeoPoint(-25.75, 28.19), // Pretoria
        "ZM" to GeoPoint(-15.39, 28.32), // Lusaka
        "ZW" to GeoPoint(-17.83, 31.05), // Harare

        // Americas
        "AR" to GeoPoint(-34.60, -58.38), // Buenos Aires
        "BO" to GeoPoint(-16.49, -68.15), // La Paz
        "BR" to GeoPoint(-15.79, -47.88), // Brasília
        "CA" to GeoPoint(45.42, -75.70), // Ottawa
        "CL" to GeoPoint(-33.45, -70.67), // Santiago
        "CO" to GeoPoint(4.71, -74.07), // Bogotá
        "CR" to GeoPoint(9.93, -84.09), // San José
        "CU" to GeoPoint(23.11, -82.37), // Havana
        "DO" to GeoPoint(18.49, -69.93), // Santo Domingo
        "EC" to GeoPoint(-0.18, -78.47), // Quito
        "GT" to GeoPoint(14.63, -90.51), // Guatemala City
        "MX" to GeoPoint(19.43, -99.13), // Mexico City
        "PA" to GeoPoint(8.98, -79.52), // Panama City
        "PE" to GeoPoint(-12.05, -77.04), // Lima
        "PR" to GeoPoint(18.47, -66.11), // San Juan
        "PY" to GeoPoint(-25.26, -57.58), // Asunción
        "US" to GeoPoint(38.90, -77.04), // Washington, D.C.
        "UY" to GeoPoint(-34.90, -56.16), // Montevideo
        "VE" to GeoPoint(10.48, -66.90), // Caracas
    )
}
