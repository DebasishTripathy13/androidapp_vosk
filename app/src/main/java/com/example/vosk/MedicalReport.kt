package com.example.vosk

/**
 * Data class representing a structured medical report extracted by the LLM.
 */
data class MedicalReport(
    val diagnosis: String,      // डॉक्टर
    val medication: String,     // दवाई (with dosage inline)
    val otherTests: String,     // जांच
    val followUp: String        // फॉलोअप
)
