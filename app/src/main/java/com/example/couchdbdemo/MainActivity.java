package com.example.couchdbdemo;

import android.os.AsyncTask;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.widget.ImageView;
import android.widget.TextView;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.Database;
import com.couchbase.lite.Document;
import com.couchbase.lite.Manager;
import com.couchbase.lite.Query;
import com.couchbase.lite.QueryEnumerator;
import com.couchbase.lite.QueryRow;
import com.couchbase.lite.android.AndroidContext;
import com.couchbase.lite.auth.Authenticator;
import com.couchbase.lite.auth.AuthenticatorFactory;
import com.couchbase.lite.replicator.Replication;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.Semaphore;
import java.util.zip.GZIPInputStream;

import org.apache.commons.io.IOUtils;

public final class MainActivity extends AppCompatActivity {

    private Document mDoc;
    private Database mDB;
    // Create global instance lock for DB synchronization
    private Semaphore mLock = new Semaphore(0, true);

    private final class Container {
        private byte [] img;
        private String name;

        public Container (String nP, byte [] iP) {
            img = iP;
            name = nP;
        }
        public String getName() {
            return name;
        }
        public byte[] getImg() {
            return img;
        }
    }

    private final class WaitForDoc extends AsyncTask<Document, Document, Void> {
        @Override
        protected Void doInBackground(Document ... doc) {
            while (true) {
                try {
                    mLock.acquire();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                publishProgress(doc [0]);
            }
        }
        @Override
        protected void onProgressUpdate(Document... doc) {
            super.onProgressUpdate(doc);
            Container cont = extractDoc(doc [0]);
            displayContainer(cont);
        }
    }

    protected Database setupDB() {
        Manager manager = null;
        try {
            manager = new Manager(new AndroidContext(getApplicationContext()), Manager.DEFAULT_OPTIONS);
        } catch (IOException e) {
            e.printStackTrace();
        };
// Create or open the database named app
        Database database = null;
        try {
            database = manager.getDatabase("db");
        } catch (CouchbaseLiteException e) {
            e.printStackTrace();
        }

        // Create replicators to push & pull changes to & from Sync Gateway.
        URL url = null;
        try {
            url = new URL("http://<your IP here>:4984/db");
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        Authenticator auth = new AuthenticatorFactory().createBasicAuthenticator("couchbase", "couchbase");
        Replication pull = database.createPullReplication(url);
        pull.setAuthenticator(auth);
        pull.setContinuous(true);
        pull.start();
        return database;
    }

    protected Document getDoc(Database db) {
        Query query = db.createAllDocumentsQuery();
        query.setStartKeyDocId("fashionPic");
        query.setLimit(1);
        QueryEnumerator rows = null;
        try {
            rows = query.run();
        } catch (CouchbaseLiteException e) {
            e.printStackTrace();
        }

        //   Document doc = database.getDocument("fashionPic");
        // Map<String, Object> props = doc.getProperties();
        QueryRow row = rows.next();
        Document doc = row.getDocument();
        doc.addChangeListener(new Document.ChangeListener() {
            // Increase lock counter signaling changed document
            @Override
            public void changed(Document.ChangeEvent event) {
                mLock.release();
            }
        });
        return doc;
    }
    protected Container extractDoc (Document doc) {
        byte [] bytes = null;
        String name = null;
        try {
            String str0 = (String) doc.getProperty("img");
            name = (String) doc.getProperty("name");
            bytes = str0.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        byte [] imgZ = Base64.decode(bytes, Base64.DEFAULT);
        String img64 = null;
        try {
            GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(imgZ));
            img64 = new String(IOUtils.toByteArray(gis));
        } catch (IOException e) {
            e.printStackTrace();
        }
        byte [] img = null;
        try {
            img = Base64.decode(img64.getBytes("UTF-8"), Base64.DEFAULT);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return new Container(name, img);
    }

    private void displayContainer(Container cont) {
        ImageView view = (ImageView) findViewById(R.id.imageDisplay);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);

        byte [] img = cont.getImg();
        Bitmap bm = BitmapFactory.decodeByteArray(img, 0, img.length);
        view.setImageBitmap(bm);

        TextView text = (TextView) findViewById(R.id.text);
        text.setText(cont.getName());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setTitle("  Latest Fashion Updates");
        mDB = setupDB();
        mDoc = getDoc(mDB);
        // Get first image and display on screen
        Container cont = extractDoc(mDoc);
        displayContainer(cont);
        // Enter infinite background loop waiting for doc update via lock
        WaitForDoc task = new WaitForDoc();
        task.execute(mDoc, mDoc);
    }
}
