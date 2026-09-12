plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.spotless)
}

spotless {
    // Pinned to the ktlint that Spotless 7.x shipped by default so the Spotless plugin bump does not
    // change the project's formatting contract (ktlint 1.8 rewrites `when` branch spacing and moves
    // trailing comments).
    val ktlintVersion = "1.5.0"
    kotlin {
        target("**/*.kt")
        // Git-ignored scratch space (audit notes, reproductions) is never compiled, so skip it.
        targetExclude("**/build/**", "docs/**")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target("**/*.kts")
        targetExclude("**/build/**")
        ktlint(ktlintVersion)
    }
}
