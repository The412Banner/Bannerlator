package com.winlator.star.store

import android.content.Intent
import android.os.Bundle
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

class GogMainActivity : ComponentActivity() {

    private var isLoggedIn by mutableStateOf(false)
    private var username by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WinlatorTheme {
                GogMainScreen(
                    isLoggedIn = isLoggedIn,
                    username = username,
                    onLoginClick = { startActivity(Intent(this@GogMainActivity, GogLoginActivity::class.java)) },
                    onViewLibrary = { startActivity(Intent(this@GogMainActivity, GogGamesActivity::class.java)) },
                    onSignOut = { signOut() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("bh_gog_prefs", 0)
        if (prefs.getString("pending_gog_exe", null) != null) {
            finish()
            return
        }
        refreshView()
    }

    private fun refreshView() {
        val prefs = getSharedPreferences("bh_gog_prefs", 0)
        val token = prefs.getString("access_token", null)
        isLoggedIn = token != null
        username = if (isLoggedIn) prefs.getString("username", "Unknown") ?: "Unknown" else ""
    }

    private fun signOut() {
        getSharedPreferences("bh_gog_prefs", 0).edit().clear().apply()
        refreshView()
    }
}

@Composable
private fun GogMainScreen(
    isLoggedIn: Boolean,
    username: String,
    onLoginClick: () -> Unit,
    onViewLibrary: () -> Unit,
    onSignOut: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoggedIn) {
            GogLoggedInCard(username = username, onViewLibrary = onViewLibrary, onSignOut = onSignOut)
        } else {
            GogLoginCard(onLoginClick = onLoginClick)
        }
    }
}

@Composable
private fun GogLoginCard(onLoginClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("GOG.com", fontSize = 32.sp, color = Color(0xFF0055FF), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            "Sign in to access your GOG game library",
            fontSize = 14.sp,
            color = Color(0xFFAAAAAA),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onLoginClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055FF)),
            shape = RoundedCornerShape(8.dp),
        ) { Text("Login with GOG", color = Color.White) }
    }
}

@Composable
private fun GogLoggedInCard(
    username: String,
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
        Text("GOG.com", fontSize = 32.sp, color = Color(0xFF0055FF), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("Signed in as: $username", fontSize = 14.sp, color = Color(0xFFCCCCCC))
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onViewLibrary,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055FF)),
            shape = RoundedCornerShape(8.dp),
        ) { Text("View Game Library", color = Color.White) }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onSignOut,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055FF)),
            shape = RoundedCornerShape(8.dp),
        ) { Text("Sign Out", color = Color.White) }
    }
}
