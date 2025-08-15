package com.example.appmind

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.FacebookSdk
import com.facebook.appevents.AppEventsLogger
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FacebookAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var googleClient: GoogleSignInClient
    private lateinit var fbCallbackManager: CallbackManager

    private val googleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken != null) {
                signInFirebaseWithGoogle(idToken)
            } else {
                toast("No se recibió el ID Token de Google")
            }
        } catch (e: ApiException) {
            Log.e("GoogleSignIn", "Fallo: ${e.statusCode}", e)
            toast("Google cancelado o fallido")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        // Firebase
        auth = FirebaseAuth.getInstance()

        // === FACEBOOK: inicialización antes de usar LoginManager ===
        FacebookSdk.setApplicationId(getString(R.string.facebook_app_id))
        FacebookSdk.setClientToken(getString(R.string.facebook_client_token))
        try { FacebookSdk.setAutoInitEnabled(true) } catch (_: Throwable) {}
        try { FacebookSdk.fullyInitialize() } catch (_: Throwable) { FacebookSdk.sdkInitialize(applicationContext) }
        try { AppEventsLogger.activateApp(application) } catch (_: Throwable) {}

        // CallbackManager + callback de Facebook (una sola vez)
        fbCallbackManager = CallbackManager.Factory.create()
        LoginManager.getInstance().registerCallback(
            fbCallbackManager,
            object : FacebookCallback<LoginResult> {
                override fun onSuccess(result: LoginResult) {
                    val credential = FacebookAuthProvider.getCredential(result.accessToken.token)
                    auth.signInWithCredential(credential)
                        .addOnCompleteListener(this@MainActivity) { task ->
                            if (task.isSuccessful) {
                                toast("Bienvenido, ${auth.currentUser?.displayName ?: "Usuario"}")
                                goToHome()
                            } else {
                                Log.e("FBAuth", "Firebase con Facebook falló", task.exception)
                                toast("No se pudo iniciar con Facebook")
                            }
                        }
                }
                override fun onCancel() { toast("Facebook cancelado") }
                override fun onError(error: FacebookException) {
                    Log.e("FacebookLogin", "Error", error)
                    toast("Error de Facebook: ${error.localizedMessage}")
                }
            }
        )

        // Google Sign-In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleClient = GoogleSignIn.getClient(this, gso)

        // Botones
        findViewById<ImageButton>(R.id.btnGoogle).setOnClickListener {
            googleLauncher.launch(googleClient.signInIntent)
        }
        findViewById<ImageButton>(R.id.btnFacebook).setOnClickListener {
            LoginManager.getInstance()
                .logInWithReadPermissions(this, listOf("email", "public_profile"))
        }
    }

    // Redirige automáticamente si ya hay sesión
    override fun onStart() {
        super.onStart()
        auth.currentUser?.let { goToHome() }
    }

    private fun signInFirebaseWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    toast("Bienvenido, ${auth.currentUser?.displayName ?: "Usuario"}")
                    goToHome()
                } else {
                    Log.e("GAuth", "Firebase con Google falló", task.exception)
                    toast("No se pudo iniciar con Google")
                }
            }
    }

    private fun goToHome() {
        // Limpia el back stack para que no vuelva a Main con "Atrás"
        val intent = Intent(this, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        finish() // cierra MainActivity
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        fbCallbackManager.onActivityResult(requestCode, resultCode, data)
    }
}
