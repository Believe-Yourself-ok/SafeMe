package com.safeme.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.emptyPreferences

enum class ThemePref {
    SYSTEM, DARK, LIGHT
}

private val Context.onboardingDataStore by preferencesDataStore(name = "onboarding_prefs")

val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
val KEY_BOUNDARIES_ACKNOWLEDGED = booleanPreferencesKey("boundaries_acknowledged")
val KEY_THEME_PREF = stringPreferencesKey("theme_pref")

suspend fun Context.markOnboardingComplete() {
    onboardingDataStore.edit { it[KEY_ONBOARDING_COMPLETE] = true }
}

fun Context.onboardingComplete(): Flow<Boolean> =
    onboardingDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_ONBOARDING_COMPLETE] ?: false }

suspend fun Context.markBoundariesAcknowledged() {
    onboardingDataStore.edit { it[KEY_BOUNDARIES_ACKNOWLEDGED] = true }
}

fun Context.boundariesAcknowledged(): Flow<Boolean> =
    onboardingDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_BOUNDARIES_ACKNOWLEDGED] ?: false }

suspend fun Context.setThemePref(pref: ThemePref) {
    onboardingDataStore.edit { it[KEY_THEME_PREF] = pref.name }
}

fun Context.themePref(): Flow<ThemePref> =
    onboardingDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            prefs[KEY_THEME_PREF]?.let { stored ->
                ThemePref.entries.firstOrNull { it.name == stored }
            } ?: ThemePref.SYSTEM
        }
