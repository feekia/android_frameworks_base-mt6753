/* //device/samples/SampleCode/src/com/android/samples/app/RemoteServiceInterface.java
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

package com.xolo.music;

import android.graphics.Bitmap;
import android.os.Messenger;

interface IMediaPlaybackService
{

    boolean isPlaybackValid();
    void cancelSingleFileDownload(in String url,in String savePath);
    void postGAEvent(in String category,in String action,in String label, in int value);
    void postGAPageView(in String page);
    void setGACustomVar(in String var, in String value);
    void openFile(String path);
    void openStream(String url, boolean resumeStream);
    void openStream2(String url);
    void open(in long [] list, int position);
    int getQueuePosition();
    boolean isPlaying();
    boolean isStreamPlaying();
    boolean isInitialized();
    boolean isStreamResumable();
    void stop();
    void stopStream();
    void stopStream2(boolean canResume, boolean abort);
    boolean isCurrentTypeStream();
    void pause();
    void pauseStream2();
    void registerPlaybackMessenger(in Messenger messenger);
    void unRegisterPlaybackMessenger();
    void registerGestureMessenger(in Messenger messenger);
    void unRegisterGestureMessenger();
    void play();
    void playStream();
    void setStreamInfo(String streamName,String artistName,String albumName, String streamId);
    void playStream2();
    void prev();
    void next();
    long duration();
    long position();
    long seek(long pos);
    String getTrackName();
    String getAlbumName();
    long getAlbumId();
    String getArtistName();
    long getArtistId();

    void setStreamTrack(String track);
    void setStreamAlbum(String album);
    void setStreamId(String streamId);
    void setStreamArtist(String artist);
    void setStreamUrl(String streamUrl);
    void setStreamName(String streamName);
    boolean setUserRating(double rating);
    boolean setUserRating2(long songId,double rating);
    void setStreamAlbumCover(in Bitmap cover);
    Bitmap getStreamAlbumCover();
    String getStreamTrackName();
    String getStreamAlbumName();
    String getStreamArtistName();
    String getStreamId();
    String getStreamName();
    String getStreamUrl();
    double getUserRating();
    double getUserRating2(long songId);
    void enqueue(in long [] list, int action);
    long [] getQueue();
    void moveQueueItem(int from, int to);
    void setQueuePosition(int index);
    String getPath();
    long getAudioId();
    void setShuffleMode(int shufflemode);
    int getShuffleMode();
    int removeTracks(int first, int last);
    int removeTrack(long id);
    void setRepeatMode(int repeatmode);
    int getRepeatMode();
    int getMediaMountedCount();
    int getAudioSessionId();

}

