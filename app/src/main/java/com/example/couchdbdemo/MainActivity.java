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
    // Create global class instance lock for DB synchronization
    private Semaphore mLock = new Semaphore(0, true);

    // Simple container for holding the image bitmap as well as the image name
    private final class Container {
        private byte [] img;
        private String name;
        public Container (String nP, byte [] iP) {
            img = iP;
            name = nP;
        }
        // Didn't bother with Lombok :-)
        public String getName() {
            return name;
        }
        public byte[] getImg() {
            return img;
        }
    }

    // Helper class for asynchronous UI updates
    // Implementation is straight forward: wait until the document has been updated
    // (using the instance-wide lock) and then passing on the contents for display
    // in the main UI thread via publishProgress
    // A bit like Hotel California: you can check out any time you like but can never
    // leave... :-)
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
        // Display updated document content in main UI thread
        @Override
        protected void onProgressUpdate(Document... doc) {
            super.onProgressUpdate(doc);
            Container cont = extractDoc(doc [0]);
            displayContainer(cont);
        }
    }

    // Set up CB instance: this is almost verbatim from the CB documentation
    private Database setupDB() {
        Manager manager = null;
        try {
            manager = new Manager(new AndroidContext(getApplicationContext()), Manager.DEFAULT_OPTIONS);
        } catch (IOException e) {
            e.printStackTrace();
        };
        Database database = null;
        try {
            database = manager.getDatabase("db");
        } catch (CouchbaseLiteException e) {
            e.printStackTrace();
        }

        // Create replicators to pull changes from Sync Gateway.
        URL url = null;
        try {
            // Slot in the IP address of the server running the Sync Gateway container (or real instance) here
            url = new URL("http://<your IP here>:4984/db");
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        Authenticator auth = new AuthenticatorFactory().createBasicAuthenticator("couchbase", "couchbase");
        Replication pull = database.createPullReplication(url);
        pull.setAuthenticator(auth);
        // This is the important bit: tell the Sync Gateway to continously update our instance upon change
        pull.setContinuous(true);
        pull.start();
        return database;
    }

    // Get document from synced bucket via the Sync Gateway based on DocID and setLimit (as we
    // only have one instance with this ID :-)
    private Document getDoc(Database db) {
        Query query = db.createAllDocumentsQuery();
        query.setStartKeyDocId("fashionPic");
        query.setLimit(1);
        QueryEnumerator rows = null;
        try {
            rows = query.run();
        } catch (CouchbaseLiteException e) {
            e.printStackTrace();
        }

        QueryRow row = rows.next();
        Document doc = row.getDocument();
        // In member function definition for AsyncTask
        doc.addChangeListener(new Document.ChangeListener() {
            // Increase lock counter signaling changed document
            @Override
            public void changed(Document.ChangeEvent event) {
                // Tell AsyncTask that we have updated content
                mLock.release();
            }
        });
        return doc;
    }

    // Extract bitmap and name into container from document
    // Essentially this means reversing the actions from the server side (decoding
    // and inflating the bitmap string) and finally creating a helper container instance which
    // is returned to the caller
    private Container extractDoc (Document doc) {
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

    // Simple helper function to display the content of a container:
    // Create ImageView to hold the image bitmap and print the image
    // name below it based on the Textview in the layout in activity_main.xml
    private void displayContainer(Container cont) {
        ImageView view = (ImageView) findViewById(R.id.imageDisplay);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);

        byte [] img = cont.getImg();
        // Standard textbook bitmap creation
        Bitmap bm = BitmapFactory.decodeByteArray(img, 0, img.length);
        view.setImageBitmap(bm);

        TextView text = (TextView) findViewById(R.id.text);
        text.setText(cont.getName());
    }

    // Start the whole thing up:
    // Retrieve the first image including its name from the Sync Gateway using the
    // above helper functions and then create an instance derived from AsyncTask to
    // display any incoming updated from the Sync Gateway (note the loop *inside* the
    // doInBackground member function in WaitForDoc)
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
        // Enter infinite background loop waiting for doc update via lock in doInBackground in WaitForDoc
        WaitForDoc task = new WaitForDoc();
        task.execute(mDoc, mDoc);
    }
}
