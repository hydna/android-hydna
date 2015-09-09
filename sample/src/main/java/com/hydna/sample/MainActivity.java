package com.hydna.sample;

import java.net.MalformedURLException;

import android.os.Bundle;
import android.app.Activity;
import android.view.Menu;

import com.hydna.*;


public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Channel channel = new Channel() {
            @Override
            public void onConnect(ChannelEvent event) {
                System.out.println("Connected to hydna, sending message");
                try {
                    send("Hello world from java!");
                } catch (ChannelException e) {
                }
             }

             @Override
             public void onMessage(ChannelEvent event) {
                 System.out.println("Received message " + event.getString());
                 try {
                     close();
                 } catch (ChannelException e) {
                 }
             }

             @Override
             public void onClose(ChannelCloseEvent event) {
                 System.out.println("Channel is now closed");
             }
        };

        try {
            channel.connect("public.hydna.net", ChannelMode.READWRITE);
        } catch (ChannelException err) {
            System.out.println("Error in channel: " + err.getMessage());
        } catch (MalformedURLException err) {
            System.out.println("url expcetion");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }
}
