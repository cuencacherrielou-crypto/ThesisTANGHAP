package com.sti.tanghap.data.remote;
import com.google.firebase.auth.FirebaseAuth;
public class FirebaseAuthRepo {
    private final FirebaseAuth auth = FirebaseAuth.getInstance();
    public void login(String email, String password){
        auth.signInWithEmailAndPassword(email,password);
    }
    public void register(String email,String password){
        auth.createUserWithEmailAndPassword(email,password);
    }
    public void logout(){
        auth.signOut();
    }

    public boolean isLoggedIn() {
        return auth.getCurrentUser() != null;
    }
}
