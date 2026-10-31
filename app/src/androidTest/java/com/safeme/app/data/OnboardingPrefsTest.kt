package com.safeme.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPrefsTest {

    @Test
    fun boundariesAcknowledgementPersists() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        context.markBoundariesAcknowledged()

        assertTrue(context.boundariesAcknowledged().first())
    }
}
