package shafi.example.nexuschat;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import android.os.Bundle;
import android.view.MenuItem;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class NexusChatActivity extends AppCompatActivity {

    private BottomNavigationView bottomNavigationView;
    private HomeFragment homeFragment;
    private CartFragment cartFragment;
    private ContactFragment contactFragment;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bottomNavigationView = findViewById(R.id.bottombar);

        homeFragment = new HomeFragment();
        cartFragment = new CartFragment();
        contactFragment = new ContactFragment();

        setourFragment(homeFragment);

        bottomNavigationView.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem menuItem) {

                switch(menuItem.getItemId()){
                    case R.id.home:
                        bottomNavigationView.setItemBackgroundResource(R.color.home);
                        setourFragment(homeFragment);
                        break;

                    case R.id.cart:
                        bottomNavigationView.setItemBackgroundResource(R.color.cart);
                        setourFragment(cartFragment);
                        break;

                    case R.id.contact:
                        bottomNavigationView.setItemBackgroundResource(R.color.contact);
                        setourFragment(contactFragment);
                        break;

                        default:
                            return false;
                }

                return false;
            }
        });
    }

    public void setourFragment(Fragment fragment){
        FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
        fragmentTransaction.replace(R.id.mainframe,fragment);
        fragmentTransaction.commit();

    }
}
