package com.example.lostfound

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser

sealed interface AuthUiState {
    data object Loading : AuthUiState
    data object Unauthenticated : AuthUiState
    data class Authenticated(val user: FirebaseUser) : AuthUiState
}

@Composable
fun RequireAuth(
    loadingContent: @Composable () -> Unit = {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    },
    unauthenticatedContent: @Composable () -> Unit = {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    },
    content: @Composable (user: FirebaseUser) -> Unit
) {
    val auth = remember { FirebaseAuth.getInstance() }
    var authState by remember { mutableStateOf<AuthUiState>(AuthUiState.Loading) }

    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            authState = if (user != null) {
                AuthUiState.Authenticated(user)
            } else {
                AuthUiState.Unauthenticated
            }
        }

        auth.addAuthStateListener(listener)

        onDispose {
            auth.removeAuthStateListener(listener)
        }
    }

    when (val state = authState) {
        AuthUiState.Loading -> loadingContent()
        AuthUiState.Unauthenticated -> unauthenticatedContent()
        is AuthUiState.Authenticated -> content(state.user)
    }
}
