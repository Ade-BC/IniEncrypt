package com.smatworld.iniencrypt.di;

import android.app.Application;

// Custom Application class that needs to be specified
// in the AndroidManifest.xml file
public class IniEncryptApp extends Application {
    // Instance of AppContainer that will be used by all the Activities of the app
    public AppContainer appContainer = new AppContainer();
}
