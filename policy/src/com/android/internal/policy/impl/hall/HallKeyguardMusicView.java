package com.android.internal.policy.impl.hall;

import android.content.AsyncQueryHandler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.xolo.music.IMediaPlaybackService;

import com.android.internal.R;

import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * NEW Feature: HALL keyguard UI
 * 
 * @author hsm
 *
 */
public class HallKeyguardMusicView extends LinearLayout implements View.OnClickListener {
    static final String TAG = "HallKeyguardMusicView";
    static final boolean DBG = true;
    
    static final String PLAYSTATE_CHANGED = "com.xolo.music.playstatechanged";
    static final String META_CHANGED = "com.xolo.music.metachanged";
	                                    
    
    ImageView mAlbum;
    TextView mTrack, mArtist;
    ImageButton mPrev, mPlay, mNext;

    IMediaPlaybackService mService = null;
    Bitmap mArtBitmap = null;
    long mArtSongId = -1;
    Worker mAlbumArtWorker;
    AlbumArtHandler mAlbumArtHandler;
    TrackQueryHandler mTrackQueryHandler;

    public HallKeyguardMusicView(Context context) {
        this(context, null);
    }
    
    public HallKeyguardMusicView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }
    
    public HallKeyguardMusicView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setOrientation(VERTICAL);
        final LayoutInflater inflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        final View v = inflater.inflate(R.layout.hall_keyguard_music, this, true);
        mAlbum = (ImageView)v.findViewById(R.id.hall_keyguard_music_album);
        mTrack = (TextView)v.findViewById(R.id.hall_keyguard_music_track);
        mArtist = (TextView)v.findViewById(R.id.hall_keyguard_music_artist);
        mPrev = (ImageButton)v.findViewById(R.id.hall_keyguard_music_prev);
        mPlay = (ImageButton)v.findViewById(R.id.hall_keyguard_music_play);
        mNext = (ImageButton)v.findViewById(R.id.hall_keyguard_music_next);
        mPrev.setOnClickListener(this);
        mPlay.setOnClickListener(this);
        mNext.setOnClickListener(this);
        
        mAlbumArtWorker = new Worker(TAG);
        mAlbumArtHandler = new AlbumArtHandler(mAlbumArtWorker.getLooper());
        mTrackQueryHandler = new TrackQueryHandler(context.getContentResolver());
    }

    
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        final Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.xolo.music", "com.xolo.music.MediaPlaybackService"));
        getContext().bindService(intent, mSC, Context.BIND_AUTO_CREATE);
        registerReceivers();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mAlbumArtWorker.quit();
        if (HallKeyguardView.IMPL_OPT) getContext().unbindService(mSC);
        unregisterReceivers();
    }

    void setPlayButtonImage() {
        try {
            if (mService != null && mService.isPlaying()) {
                mPlay.setImageResource(android.R.drawable.ic_media_pause);
            } else {
                mPlay.setImageResource(android.R.drawable.ic_media_play);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "setPlayButtonImage: " + e);
        }
    }

    @Override public void onClick(View v) {
        switch (v.getId()) {
            case R.id.hall_keyguard_music_prev:
                playPrev();
                break;

            case R.id.hall_keyguard_music_play:
                playOrPause();
                break;
                
            case R.id.hall_keyguard_music_next:
                playNext();
                break;
        }
    }
    
    boolean isPlaying() {
        boolean isPlaying = false;
        if (mService != null) {
            try {
                isPlaying = mService.isPlaying();
            } catch (RemoteException e) {
                Log.e(TAG, "updatePlayImage: " + e);
            }
        }
        return isPlaying;
    }

    void playPrev() {
        if (mService != null) {
            try {
                mService.prev();
            } catch (RemoteException e) {
                Log.e(TAG, "playPrev: " + e);
            }
        }
    }
    
    void playNext() {
        if (mService != null) {
            try {
                mService.next();
            } catch (RemoteException e) {
                Log.e(TAG, "playNext: " + e);
            }
        }
    }
    
    void playOrPause() {
        if (mService != null) {
            try {
                final boolean isPlaying = mService.isPlaying();
                if (isPlaying) {
                    mService.pause();
                } else {
                    mService.play();
                }
                setPlayButtonImage();
            } catch (RemoteException e) {
                Log.e(TAG, "playOrPause: " + e);
            }
        }
    }
    
    
    void updateTrackInfo() {
        if (mService != null) {
            try {
                final String path = mService.getPath();
                if (path != null) {
                    Log.d(TAG, "updateTrackInfo: path=" + path);
                    final long songid = mService.getAudioId(); 
                    if (songid < 0 && path.toLowerCase().startsWith("http://")) {
                        mTrack.setText(path);
                        mAlbumArtHandler.removeMessages(GET_ALBUM_ART);
                        mAlbumArtHandler.obtainMessage(GET_ALBUM_ART, new AlbumSongIdWrapper(-1, -1)).sendToTarget();
                    } else {
                        String artistName = mService.getArtistName();
                        if (MediaStore.UNKNOWN_STRING.equals(artistName)) {
                            artistName = "Unknown";
                        }
                        mArtist.setText(artistName);						
                        String albumName = mService.getAlbumName();
                        long albumid = mService.getAlbumId();
                        if (MediaStore.UNKNOWN_STRING.equals(albumName)) {
                            albumName = "Unknown";
                            albumid = -1;
                        }
                        mTrack.setText(mService.getTrackName());
						mTrack.setTextColor(android.graphics.Color.WHITE);
						mArtist.setTextColor(android.graphics.Color.WHITE);
                        mAlbumArtHandler.removeMessages(GET_ALBUM_ART);
                        mAlbumArtHandler.obtainMessage(GET_ALBUM_ART, new AlbumSongIdWrapper(albumid, songid)).sendToTarget();
                    }
                } else {
                    Log.w(TAG, "updateTrackInfo: getPath is NULL.");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "updateTrackInfo: " + e + ", call stack=" + new Throwable());
            }
        }
    }

    void registerReceivers() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(PLAYSTATE_CHANGED);
        filter.addAction(META_CHANGED);
        getContext().registerReceiver(mStatusListener, filter);
    }

    void unregisterReceivers() {
        getContext().unregisterReceiver(mStatusListener);
    }
    
    
    static final String[] sCursorCols = new String[] {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.DURATION,
    };

    final class TrackQueryHandler extends AsyncQueryHandler {
        final class QueryArgs {
            public Uri uri;
            public String [] projection;
            public String selection;
            public String [] selectionArgs;
            public String orderBy;
        }
        
        public TrackQueryHandler(ContentResolver cr) {
            super(cr);
        }
        
        public Cursor doQuery(Uri uri, String[] projection,
                String selection, String[] selectionArgs, String orderBy, boolean async) {
            if (async) {
                Uri limituri = uri.buildUpon().appendQueryParameter("limit", "2").build();
                QueryArgs args = new QueryArgs();
                args.uri = uri;
                args.projection = projection;
                args.selection = selection;
                args.selectionArgs = selectionArgs;
                args.orderBy = orderBy;

                startQuery(0, args, limituri, projection, selection, selectionArgs, orderBy);
                return null;
            }
            return query(getContext(), uri, projection, selection, selectionArgs, orderBy, 2);
        }
        
        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            Log.d(TAG, "query complete: count is " + (cursor == null ? null : cursor.getCount()));
            if (token == 0 && cookie != null && cursor != null && !cursor.isClosed() && cursor.getCount() >= 2) {
                QueryArgs args = (QueryArgs) cookie;
                startQuery(1, null, args.uri, args.projection, args.selection, args.selectionArgs, args.orderBy);
            }
        }
    }
    
    
    static Cursor query(Context context, Uri uri, String[] projection, String selection, 
            String[] selectionArgs, String sortOrder, int limit) {
        try {
            ContentResolver resolver = context.getContentResolver();
            if (resolver == null) {
                Log.e(TAG, "query: resolver is NULL");
                return null;
            }
            if (limit > 0) {
                uri = uri.buildUpon().appendQueryParameter("limit", "" + limit).build();
            }
            return resolver.query(uri, projection, selection, selectionArgs, sortOrder);
         } catch (UnsupportedOperationException ex) {
            Log.e(TAG, "query: " + ex);
            return null;
        }
    }

    static final Cursor getTrackCursor(TrackQueryHandler queryhandler, String filter, boolean async) {
        if (queryhandler == null) {
            Log.e(TAG, "getTrackCursor: queryhandler is NULL.");
            return null;
        }

        final StringBuilder where = new StringBuilder();
        where.append(MediaStore.Audio.Media.IS_MUSIC + "=1");
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        if (!TextUtils.isEmpty(filter)) {
            uri = uri.buildUpon().appendQueryParameter("filter", Uri.encode(filter)).build();
        }
        return queryhandler.doQuery(uri, sCursorCols, where.toString(), null,
                MediaStore.Audio.Genres.Members.DEFAULT_SORT_ORDER, async);
    }

    final BroadcastReceiver mStatusListener = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            Log.d(TAG, "mStatusListener: " + action);
            if (META_CHANGED.equals(action)) {
                mHandler.post(new Runnable() {
                    @Override public void run() {
                        updateTrackInfo();
                        setPlayButtonImage();
                    }
                });
            } else if (PLAYSTATE_CHANGED.equals(action)) {
                mHandler.post(new Runnable() {
                    @Override public void run() {
                        setPlayButtonImage();
                    }
                });
            }
        }
    };
    
    void openMusicPathIfNeeded() {
        if (mService != null) {
            try {
                String path = mService.getPath();
                if (path == null) {
                    Log.w(TAG, "openMusicPathIfNeeded: path is NULL.");
                    final Cursor cursor = getTrackCursor(mTrackQueryHandler, null, false);
                    if (cursor != null) {
                        cursor.moveToFirst();
                        path = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI + "/" + cursor.getLong(0);
                        Log.d(TAG, "openMusicPathIfNeeded: path=" + path);
                        mService.openFile(path);
                    } else {
                        Log.e(TAG, "openMusicPathIfNeeded: cursor is NULL.");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "onServiceConnected: " + e);
            }
        }
    }

    final ServiceConnection mSC = new ServiceConnection() {
        public void onServiceConnected(ComponentName classname, IBinder obj) {
            mService = IMediaPlaybackService.Stub.asInterface(obj);
            if (mService != null) {        
			   /**
			   *  0328 sometime SD card auto play music
			   */        
               // openMusicPathIfNeeded();
                mHandler.post(new Runnable() {
                    @Override public void run() {
                        updateTrackInfo();
                        setPlayButtonImage();
                    }
                });
            }
        }
        public void onServiceDisconnected(ComponentName classname) {
            mService = null;
        }
    };
    
    final class AlbumArtHandler extends Handler {
        private long mAlbumId = -1;

        public AlbumArtHandler(Looper looper) {
            super(looper);
        }
        
        @Override
        public void handleMessage(Message msg) {
            /// M: Keep album art in mArtBitmap to improve loading speed when config changed.
            long albumid = ((AlbumSongIdWrapper) msg.obj).albumid;
            long songid = ((AlbumSongIdWrapper) msg.obj).songid;
            Log.d(TAG, "AlbumArtHandler. mAlbumId = " + mAlbumId + " ,albumid = " + albumid + ", albumid = " + albumid);
            if (msg.what == GET_ALBUM_ART && (mAlbumId != albumid || albumid < 0)){
                Message numsg = null;
                // while decoding the new image, show the default album art
                if (mArtBitmap == null || mArtSongId != songid) {
                    numsg = mHandler.obtainMessage(ALBUM_ART_DECODED, null);
                    mHandler.removeMessages(ALBUM_ART_DECODED);
                    mHandler.sendMessageDelayed(numsg, 300);

                    // Don't allow default artwork here, because we want to fall back to song-specific
                    // album art if we can't find anything for the album.
                    /// M: if don't get album art from file,or the album art is not the same 
                    /// as the song ,we should get the album art again
                    mArtBitmap = getArtwork(getContext(), songid, albumid, false);
                    Log.d(TAG, "AlbumArtHandler. mArtSongId = " + mArtSongId + " ,songid = " + songid + " ");
                    mArtSongId = songid;
                }
                
                if (mArtBitmap == null) {
                    mArtBitmap = getDefaultArtwork(getContext());
                    albumid = -1;
                }
                if (mArtBitmap != null) {
                    numsg = mHandler.obtainMessage(ALBUM_ART_DECODED, mArtBitmap);
                    mHandler.removeMessages(ALBUM_ART_DECODED);
                    mHandler.sendMessage(numsg);
                }
                mAlbumId = albumid;
            }
        }
    }
    
    static final BitmapFactory.Options sBitmapOptions = new BitmapFactory.Options();
    static final Uri sArtworkUri = Uri.parse("content://media/external/audio/albumart");
    static final Bitmap getArtwork(Context context, long song_id, long album_id,  boolean allowdefault) {
        Log.d(TAG, ">> getArtWork, song_id=" + song_id + ", album_id=" + album_id);
		
          if (album_id < 0) {
            // This is something that is not in the database, so get the album art directly
            // from the file.
		
            if (song_id >= 0) {
                Bitmap bm = getArtworkFromFile(context, song_id, -1);
                if (bm != null) {
                    return bm;
                }
            }
            if (allowdefault) {
                return getDefaultArtwork(context);
            }
            return null;
        }

        ContentResolver res = context.getContentResolver();
        Uri uri = ContentUris.withAppendedId(sArtworkUri, album_id);
        if (uri != null) {
            InputStream in = null;
            try {
                in = res.openInputStream(uri);
               return BitmapFactory.decodeStream(in, null, sBitmapOptions);
            } catch (FileNotFoundException ex) {
                // The album art thumbnail does not actually exist. Maybe the user deleted it, or
                // maybe it never existed to begin with.
                Log.w(TAG, "getArtWork: open " + uri.toString() + " failed, try getArtworkFromFile");
                Bitmap bm = getArtworkFromFile(context, song_id, album_id);
                if (bm != null) {
                    if (bm.getConfig() == null) {
                        bm = bm.copy(Bitmap.Config.RGB_565, false);
                        if (bm == null && allowdefault) {
                            return getDefaultArtwork(context);
                        }
                    }
					Log.w(TAG, "getArtWork: open  bm != null " + uri.toString() + " failed, try getArtworkFromFile");
                } else if (allowdefault) {
                    bm = getDefaultArtwork(context);
                }
                return bm;
            } finally {
                try {
                    if (in != null) {
                        in.close();
                    }
                } catch (IOException ex) {
                }
            }
        }
        
        return null;
    }
    
    
    private static final int GET_ALBUM_ART = 3;
    private static final int ALBUM_ART_DECODED = 4;
    final Handler mHandler = new Handler() {
        @Override public void handleMessage(Message msg) {
            switch (msg.what) {
                case ALBUM_ART_DECODED:
                    mAlbum.setImageBitmap((Bitmap)msg.obj);
                    mAlbum.getDrawable().setDither(true);
                    break;
            }
        }
    };
    

    static Bitmap sCachedBit = null;
    static final String sExternalMediaUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString();
    static final Bitmap getArtworkFromFile(Context context, long songid, long albumid) {
        Log.d(TAG, ">> getArtworkFromFile, songid=" + songid + ", albumid=" + albumid);
        Bitmap bm = null;
        byte [] art = null;
        String path = null;

        if (albumid < 0 && songid < 0) {
            //throw new IllegalArgumentException("Must specify an album or a song id");
            Log.e(TAG, "Must specify an album or a song id!");
            return null;
        }
        
        ParcelFileDescriptor pfd = null;
        try {
            if (albumid < 0) {
                Uri uri = Uri.parse("content://media/external/audio/media/" + songid + "/albumart");
                pfd = context.getContentResolver().openFileDescriptor(uri, "r");
                Log.d(TAG, "getArtworkFromFile: pFD=" + pfd);
                if (pfd != null) {
                    FileDescriptor fd = pfd.getFileDescriptor();
                    bm = BitmapFactory.decodeFileDescriptor(fd);
                }
            } else {
                Uri uri = ContentUris.withAppendedId(sArtworkUri, albumid);
                pfd = context.getContentResolver().openFileDescriptor(uri, "r");
                Log.d(TAG, "getArtworkFromFile: pFD=" + pfd);
                if (pfd != null) {
                    FileDescriptor fd = pfd.getFileDescriptor();
                    bm = BitmapFactory.decodeFileDescriptor(fd);
                }
            }
        } catch (IllegalStateException ex) {
            Log.e(TAG, "getArtworkFromFile: " + ex);
        } catch (FileNotFoundException ex) {
            Log.e(TAG, "getArtworkFromFile: " + ex);
        } finally {
            try {
                if (pfd != null){
                    pfd.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "fd.close: IOException!");
            }
        }
        if (bm != null) {
            sCachedBit = bm;
        }
        Log.d(TAG, "<< getArtworkFromFile: " + bm);
        return bm;
    }
    
    static final class AlbumSongIdWrapper {
        long albumid;
        long songid;
        
        AlbumSongIdWrapper(long aid, long sid) {
            albumid = aid;
            songid = sid;
        }
    }
    
    static final Bitmap getDefaultArtwork(Context context) {
        Log.d(TAG, "getDefaultArtwork");
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeStream(context.getResources().openRawResource(R.drawable.albumart_mp_unknown), null, opts);
    }
    
    static final class Worker implements Runnable {
        final Object mLock = new Object();
        Looper mLooper;
        
        Worker(String name) {
            Thread t = new Thread(null, this, name);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
            synchronized (mLock) {
                while (mLooper == null) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException ex) {
                    }
                }
            }
        }
        
        public Looper getLooper() {
            return mLooper;
        }
        
        public void run() {
            synchronized (mLock) {
                Looper.prepare();
                mLooper = Looper.myLooper();
                mLock.notifyAll();
            }
            Looper.loop();
        }
        
        public void quit() {
            mLooper.quit();
        }
    }
}
