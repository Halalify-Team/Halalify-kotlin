package com.halalify.kotlin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.halalify.kotlin.ui.theme.HalalifyAccent
import com.halalify.kotlin.ui.theme.HalalifyDarkCard
import com.halalify.kotlin.ui.theme.HalalifySuccess
import com.halalify.kotlin.ui.theme.HalalifyTextOnAccent
import com.halalify.kotlin.ui.theme.HalalifyTextPrimary
import com.halalify.kotlin.ui.theme.HalalifyTextSecondary
import com.halalify.kotlin.ui.theme.HalalifyTextTertiary
import com.halalify.kotlin.ui.theme.HalalifyWarning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileScreen(
    backendUrl: String,
    devEmail: String,
    sessionToken: String,
    loginStatus: String,
    isLoggingIn: Boolean,
    onBackendUrlChange: (String) -> Unit,
    onDevEmailChange: (String) -> Unit,
    onDevLogin: () -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
) {
    val isSignedIn = sessionToken.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Profile",
                        fontWeight = FontWeight.Bold,
                        color = HalalifyTextPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = HalalifyTextPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AccountCard(
                email = devEmail,
                isSignedIn = isSignedIn,
                loginStatus = loginStatus,
            )

            SettingsCard(
                backendUrl = backendUrl,
                devEmail = devEmail,
                isSignedIn = isSignedIn,
                sessionToken = sessionToken,
                isLoggingIn = isLoggingIn,
                onBackendUrlChange = onBackendUrlChange,
                onDevEmailChange = onDevEmailChange,
                onDevLogin = onDevLogin,
                onLogout = onLogout,
            )

            QuotaPreviewCard()
        }
    }
}

@Composable
private fun AccountCard(
    email: String,
    isSignedIn: Boolean,
    loginStatus: String,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = HalalifyDarkCard),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint = HalalifyAccent,
                    modifier = Modifier.size(44.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        text = if (isSignedIn) "Signed in" else "Not signed in",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isSignedIn) HalalifySuccess else HalalifyWarning,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = email.ifBlank { "No email configured" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = HalalifyTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (isSignedIn) HalalifySuccess else HalalifyTextTertiary,
                )
            }

            if (loginStatus.isNotBlank()) {
                Text(
                    text = loginStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (loginStatus.startsWith("SUCCESS")) HalalifySuccess else HalalifyTextSecondary,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    backendUrl: String,
    devEmail: String,
    isSignedIn: Boolean,
    sessionToken: String,
    isLoggingIn: Boolean,
    onBackendUrlChange: (String) -> Unit,
    onDevEmailChange: (String) -> Unit,
    onDevLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = HalalifyDarkCard),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Connection",
                style = MaterialTheme.typography.titleMedium,
                color = HalalifyTextPrimary,
                fontWeight = FontWeight.Bold,
            )

            OutlinedTextField(
                value = backendUrl,
                onValueChange = onBackendUrlChange,
                label = { Text("Backend URL") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = null,
                        tint = HalalifyAccent,
                    )
                },
                singleLine = true,
                colors = profileTextFieldColors(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = devEmail,
                onValueChange = onDevEmailChange,
                label = { Text("Account Email") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = null,
                        tint = HalalifyAccent,
                    )
                },
                singleLine = true,
                colors = profileTextFieldColors(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            SessionTokenRow(sessionToken = sessionToken)

            Button(
                onClick = if (isSignedIn) onLogout else onDevLogin,
                enabled = !isLoggingIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSignedIn) MaterialTheme.colorScheme.surfaceVariant else HalalifyAccent,
                    contentColor = if (isSignedIn) HalalifyTextPrimary else HalalifyTextOnAccent,
                ),
            ) {
                if (isLoggingIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = HalalifyAccent,
                    )
                } else {
                    Icon(
                        imageVector = if (isSignedIn) Icons.Default.Logout else Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = if (isSignedIn) "Sign Out" else "Sign In",
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionTokenRow(sessionToken: String) {
    val label = if (sessionToken.isBlank()) {
        "No session token"
    } else {
        "Token: ${sessionToken.take(8)}...${sessionToken.takeLast(6)}"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.VpnKey,
            contentDescription = null,
            tint = HalalifyTextTertiary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = HalalifyTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun QuotaPreviewCard() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = HalalifyDarkCard),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Quota",
                style = MaterialTheme.typography.titleMedium,
                color = HalalifyTextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Backend quota endpoints are available. The next task will fetch live minutes, enforce preflight checks, and handle quota exhaustion cleanly.",
                style = MaterialTheme.typography.bodyMedium,
                color = HalalifyTextSecondary,
            )
        }
    }
}

@Composable
private fun profileTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = HalalifyAccent,
    unfocusedBorderColor = HalalifyTextTertiary,
    focusedLabelColor = HalalifyAccent,
    cursorColor = HalalifyAccent,
)
