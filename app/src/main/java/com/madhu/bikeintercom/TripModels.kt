package com.madhu.bikeintercom

data class ParsedTrip(
    val start: String = "",
    val stops: List<String> = emptyList(),
    val destination: String = "",
    val preferences: List<String> = listOf("fastest")
)
