package com.example.mainproject.data.model

data class RegistrationRequest(
    val process_id: Int,
    val division: String,
    val worker_id: Int,
    val device_id: String,
    val registered_at: String,
    val product_numbers: List<String>
)

data class RegistrationResponse(
    val success: Boolean,
    val message: String? = null
)
