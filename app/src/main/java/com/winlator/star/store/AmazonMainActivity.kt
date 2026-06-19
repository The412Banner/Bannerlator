package com.winlator.star.store

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.ui.theme.WinlatorTheme

class AmazonMainActivity : ComponentActivity() {

    private var isLoggedIn by mutableStateOf(false)
    private var statusText by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WinlatorTheme {
                AmazonMainScreen(
                    isLoggedIn = isLoggedIn,
                    statusText = statusText,
                    onLoginClick = { startActivity(Intent(this@AmazonMainActivity, AmazonLoginActivity::class.java)) },
                    onViewLibrary = { startActivity(Intent(this@AmazonMainActivity, AmazonGamesActivity::class.java)) },
                    onSignOut = { signOut() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshView()
    }

    private fun refreshView() {
        val loggedIn = AmazonCredentialStore.isLoggedIn(this)
        isLoggedIn = loggedIn

        if (loggedIn) {
            val creds = AmazonCredentialStore.load(this)
            if (creds != null) {
                val minutesLeft = (creds.expiresAt - System.currentTimeMillis()) / 60000L
                statusText = "Signed in to Amazon Games\nToken expires in ~${minutesLeft}min"
            }
        }
    }

    private fun signOut() {
        val creds = AmazonCredentialStore.load(this)
        if (creds != null && creds.accessToken != null) {
            val token = creds.accessToken
            Thread { AmazonAuthClient.deregisterDevice(token) }.start()
        }
        AmazonCredentialStore.clear(this)
        refreshView()
        Toast.makeText(this, "Signed out of Amazon Games", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun AmazonMainScreen(
    isLoggedIn: Boolean,
    statusText: String,
    onLoginClick: () -> Unit,
    onViewLibrary: () -> Unit,
    onSignOut: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoggedIn) {
            AmazonLoggedInCard(statusText = statusText, onViewLibrary = onViewLibrary, onSignOut = onSignOut)
        } else {
            AmazonLoginCard(onLoginClick = onLoginClick)
        }
    }
}

@Composable
private fun AmazonLoginCard(onLoginClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Amazon Games", fontSize = 32.sp, color = Color(0xFF0055FF), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            "Sign in to access your Amazon game library",
            fontSize = 14.sp,
            color = Color(0xFFAAAAAA),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onLoginClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055FF)),
            modifier = Modifier.height(48.dp),
            shape = RoundedCornerShape(8.dp),
        ) { Text("Login with Amazon", color = Color.White) }
    }
}

@Composable
private fun AmazonLoggedInCard(
    statusText: String,
    onViewLibrary: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Amazon Games", fontSize = 32.sp, color = Color(0xFF0055FF), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(statusText, fontSize = 13.sp, color = Color(0xFFCCCCCC))
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onViewLibrary,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055FF)),
            modifier = Modifier.height(48.dp),
            shape = RoundedCornerShape(8.dp),
        ) { Text("View Game Library", color = Color.White) }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onSignOut,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055FF)),
            modifier = Modifier.height(48.dp),
            shape = RoundedCornerShape(8.dp),
        ) { Text("Sign Out", color = Color.White) }
    }
}
