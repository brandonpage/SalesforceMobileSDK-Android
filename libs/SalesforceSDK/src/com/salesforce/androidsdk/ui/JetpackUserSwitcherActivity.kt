package com.salesforce.androidsdk.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salesforce.androidsdk.R
import org.intellij.lang.annotations.JdkConstants.HorizontalAlignment

class JetpackUserSwitcherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContentView(
            ComposeView(this).apply {
                setContent {
                    UserSwitcher()
                }
            }
        )
    }


    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun UserSwitcher() {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
            val sheetState = rememberModalBottomSheetState()

            val accounts = listOf(
                Pair("Brandon Page", "login.salesforce.com"),
                Pair("Test Account", "login.salesforce.com"),
            )

            ModalBottomSheet(
                onDismissRequest = {
                    this@JetpackUserSwitcherActivity.finish()
                },
                sheetState = sheetState,
                containerColor = Color.White,
            ) {
                Text(
                    text = "Select an Account",
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.padding(10.dp))
                accounts.forEach { account ->
                    HorizontalDivider(thickness = 1.dp)
                    UserAccount(account)
                }
                HorizontalDivider(thickness = 1.dp)

                Row(
                    modifier = Modifier.fillMaxWidth().padding(15.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "More Options",
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    TextButton(
                        onClick = { }
                    ) {
                        Text(
                            text = "Add New Account",
                            color = Color.Black,
                        )
                    }
                }
            }
        }
    }

    @Preview
    @Composable
    fun UserAccount(account: Pair<String, String> = Pair("test", "url.com")) {
        Card(
            modifier = Modifier
                .padding(top = 5.dp, bottom = 5.dp)
                .fillMaxWidth()
                .clickable {
                    // switch to account
                },
            colors = CardColors(
                containerColor = Color.White,
                contentColor = Color.Black,
                disabledContentColor = Color.Gray,
                disabledContainerColor = Color.Black,
            ),
            shape = RectangleShape,

        ) {
            Row {
                Image(
                    painter = painterResource(R.drawable.sf__android_astro),
                    contentDescription = "avatar",
                    modifier = Modifier.padding(15.dp)
                )
                Column {
                    Text(account.first, modifier = Modifier.padding(start = 15.dp, end = 15.dp, top = 15.dp), fontSize = 22.sp)
                    Text(account.second, modifier = Modifier.padding(start = 15.dp, end = 15.dp, bottom = 15.dp), fontStyle = FontStyle.Italic)
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.End,
                ) {
                    if (account.first == "Brandon Page") {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "More Options",
                            modifier = Modifier.padding(15.dp)
                        )
                    }
                }
            }
        }
    }
}