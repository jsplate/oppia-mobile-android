package org.digitalcampus.oppia.service;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.preference.PreferenceManager;
import android.util.Log;

import com.splunk.mint.Mint;

import org.apache.http.params.CoreProtocolPNames;
import org.digitalcampus.mobile.learning.R;
import org.digitalcampus.oppia.activity.PrefsActivity;
import org.digitalcampus.oppia.application.DatabaseManager;
import org.digitalcampus.oppia.application.DbHelper;
import org.digitalcampus.oppia.application.MobileLearning;
import org.digitalcampus.oppia.exception.InvalidXMLException;
import org.digitalcampus.oppia.model.Course;
import org.digitalcampus.oppia.utils.HTTPConnectionUtils;
import org.digitalcampus.oppia.utils.SearchUtils;
import org.digitalcampus.oppia.utils.storage.FileUtils;
import org.digitalcampus.oppia.utils.xmlreaders.CourseScheduleXMLReader;
import org.digitalcampus.oppia.utils.xmlreaders.CourseTrackerXMLReader;
import org.digitalcampus.oppia.utils.xmlreaders.CourseXMLReader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Locale;


public class CourseIntallerService extends IntentService {

    public static final String TAG = DownloadService.class.getSimpleName();
    public static final String BROADCAST_ACTION = "com.digitalcampus.oppia.COURSEINSTALLERSERVICE";

    public static final String SERVICE_ACTION = "action";
    public static final String SERVICE_URL = "fileurl"; //field for providing file URL
    public static final String SERVICE_SHORTNAME = "shortname"; //field for providing Course shortname
    public static final String SERVICE_VERSIONID = "versionid"; //field for providing file URL
    public static final String SERVICE_MESSAGE = "message";

    public static final String ACTION_CANCEL = "cancel";
    public static final String ACTION_DOWNLOAD = "download";
    public static final String ACTION_INSTALL = "install";
    public static final String ACTION_COMPLETE = "complete";
    public static final String ACTION_FAILED = "failed";

    private ArrayList<String> tasksCancelled;
    private ArrayList<String> tasksDownloading;
    private SharedPreferences prefs;

    private static CourseIntallerService currentInstance;
    private static void setInstance(CourseIntallerService instance){
        currentInstance = instance;
    }

    public CourseIntallerService() { super(TAG); }

    @Override
    public void onCreate(){
        super.onCreate();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        CourseIntallerService.setInstance(this);

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent.hasExtra(SERVICE_ACTION) && intent.hasExtra(SERVICE_URL)) {
            // Set the canceling flag to that file
            if (intent.getStringExtra(SERVICE_ACTION).equals(ACTION_CANCEL)){
                Log.d(TAG, "CANCEL commmand received");
                addCancelledTask(intent.getStringExtra(SERVICE_URL));
            }
            else if (intent.getStringExtra(SERVICE_ACTION).equals(ACTION_DOWNLOAD)) {
                addDownloadingTask(intent.getStringExtra(SERVICE_URL));
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void onHandleIntent(Intent intent) {

        if (intent.hasExtra (SERVICE_ACTION)) {
            boolean cancel = intent.getStringExtra(SERVICE_ACTION).equals(ACTION_CANCEL);
            //We have nothing more to do with a 'cancel' action than what is done in onStartCommand()
            if (cancel) { return; }
        }

        if (!intent.hasExtra(SERVICE_URL)){
            Log.d(TAG, "No Course passed to the service. Invalid task");
            return;
        }

        String fileUrl = intent.getStringExtra(SERVICE_URL);
        String shortname = intent.getStringExtra(SERVICE_SHORTNAME);
        String versionID = intent.getStringExtra(SERVICE_VERSIONID);

        if (isCancelled(fileUrl)) {
            //If it was cancelled before starting, we do nothing
            Log.d(TAG, "Course " + fileUrl + " cancelled before started.");
            removeCancelled(fileUrl);
            removeDownloading(fileUrl);
            return;
        }

        boolean success = downloadCourseFile(fileUrl, shortname, versionID);
        if (success){ installDownloadedCourse(fileUrl, shortname, versionID); }

    }

    private void installDownloadedCourse(String fileUrl, String shortname, String versionID) {
        File tempdir = new File(FileUtils.getStorageLocationRoot(this) + "temp/");
        String filename = getLocalFilename(shortname, versionID);
        File zipFile = new File(FileUtils.getDownloadPath(this), filename);
        tempdir.mkdirs();

        sendBroadcast(fileUrl, ACTION_INSTALL, ""+1);
        boolean unzipResult = FileUtils.unzipFiles(FileUtils.getDownloadPath(this), filename, tempdir.getAbsolutePath());

        if (!unzipResult){
            //then was invalid zip file and should be removed
            FileUtils.cleanUp(tempdir, FileUtils.getDownloadPath(this) + filename);
            sendBroadcast(fileUrl, ACTION_FAILED, "" + this.getString(R.string.error_installing_course, shortname));
            removeDownloading(fileUrl);
            return;
        }
        String[] courseDirs = tempdir.list(); // use this to get the course name

        sendBroadcast(fileUrl, ACTION_INSTALL, "" + 10);

        String courseXMLPath = "";
        String courseScheduleXMLPath = "";
        String courseTrackerXMLPath = "";
        // check that it's unzipped etc correctly
        try {
            courseXMLPath = tempdir + File.separator + courseDirs[0] + File.separator + MobileLearning.COURSE_XML;
            courseScheduleXMLPath = tempdir + File.separator + courseDirs[0] + File.separator + MobileLearning.COURSE_SCHEDULE_XML;
            courseTrackerXMLPath = tempdir + File.separator + courseDirs[0] + File.separator + MobileLearning.COURSE_TRACKER_XML;
        } catch (ArrayIndexOutOfBoundsException aioobe){
            FileUtils.cleanUp(tempdir, FileUtils.getDownloadPath(this) + filename);
            logAndNotifyError(fileUrl, aioobe);
            return;
        }

        // check a module.xml file exists and is a readable XML file
        CourseXMLReader cxr;
        CourseScheduleXMLReader csxr;
        CourseTrackerXMLReader ctxr;
        try {
            cxr = new CourseXMLReader(courseXMLPath, 0, this);
            csxr = new CourseScheduleXMLReader(courseScheduleXMLPath);
            ctxr = new CourseTrackerXMLReader(courseTrackerXMLPath);
        } catch (InvalidXMLException e) {
            logAndNotifyError(fileUrl, e);
            return;
        }

        Course c = new Course(prefs.getString(PrefsActivity.PREF_STORAGE_LOCATION, ""));
        c.setVersionId(cxr.getVersionId());
        c.setTitles(cxr.getTitles());
        c.setShortname(courseDirs[0]);
        c.setImageFile(cxr.getCourseImage());
        c.setLangs(cxr.getLangs());
        c.setDescriptions(cxr.getDescriptions());
        c.setPriority(cxr.getPriority());
        String title = c.getTitle(prefs.getString(PrefsActivity.PREF_LANGUAGE, Locale.getDefault().getLanguage()));

        sendBroadcast(fileUrl, ACTION_INSTALL, ""+20);

        boolean success = false;

        DbHelper db = new DbHelper(this);
        long added = db.addOrUpdateCourse(c);
        if (added != -1) {
            File src = new File(tempdir + File.separator + courseDirs[0]);
            File dest = new File(FileUtils.getCoursesPath(this));

            db.insertActivities(cxr.getActivities(added));
            sendBroadcast(fileUrl, ACTION_INSTALL, "" + 50);

            db.insertTrackers(ctxr.getTrackers(), added);
            sendBroadcast(fileUrl, ACTION_INSTALL, "" + 70);

            // Delete old course
            File oldCourse = new File(FileUtils.getCoursesPath(this) + courseDirs[0]);
            FileUtils.deleteDir(oldCourse);

            // move from temp to courses dir
            success = src.renameTo(new File(dest, src.getName()));

            if (!success) {
                sendBroadcast(fileUrl, ACTION_FAILED, "" + this.getString(R.string.error_installing_course, title));
                removeDownloading(fileUrl);
                return;
            }
        }  else {
            sendBroadcast(fileUrl, ACTION_FAILED, "" + this.getString(R.string.error_latest_already_installed, title));
            removeDownloading(fileUrl);
        }
        // add schedule
        // put this here so even if the course content isn't updated the schedule will be
        db.insertSchedule(csxr.getSchedule());
        db.updateScheduleVersion(added, csxr.getScheduleVersion());
        DatabaseManager.getInstance().closeDatabase();

        sendBroadcast(fileUrl, ACTION_INSTALL, "" + 80);
        if (success){ SearchUtils.indexAddCourse(this, c); }

        // delete temp directory
        FileUtils.deleteDir(tempdir);
        sendBroadcast(fileUrl, ACTION_INSTALL, "" + 90);

        // delete zip file from download dir
        zipFile.delete();

        Log.d(TAG, fileUrl + " succesfully downloaded");
        removeDownloading(fileUrl);
        sendBroadcast(fileUrl, ACTION_COMPLETE, null);
    }

    @Override
    public void onDestroy(){
        super.onDestroy();
        CourseIntallerService.setInstance(null);
    }

    private void logAndNotifyError(String fileUrl, Exception e){
        e.printStackTrace();
        Log.d(TAG, "Error: " + e.getMessage());
        sendBroadcast(fileUrl, ACTION_FAILED, this.getString(R.string.error_media_download));
        removeDownloading(fileUrl);
    }

    /*
    * Sends a new Broadcast with the results of the action
    */
    private void sendBroadcast(String fileUrl, String result, String message){

        Intent localIntent = new Intent(BROADCAST_ACTION);
        localIntent.putExtra(SERVICE_ACTION, result);
        localIntent.putExtra(SERVICE_URL, fileUrl);
        if (message != null){
            localIntent.putExtra(SERVICE_MESSAGE, message);
        }
        // Broadcasts the Intent to receivers in this app.
        Log.d(TAG, fileUrl + "=" + result + ":" + message);
        sendOrderedBroadcast(localIntent, null);

    }

    private void addCancelledTask(String fileUrl){
        if (tasksCancelled == null){
            tasksCancelled = new ArrayList<String>();
        }
        if (!tasksCancelled.contains(fileUrl)){
            tasksCancelled.add(fileUrl);
        }
    }

    private boolean isCancelled(String fileUrl){
        return (tasksCancelled != null) && (tasksCancelled.contains(fileUrl));
    }

    private boolean removeCancelled(String fileUrl) {
        return tasksCancelled != null && tasksCancelled.remove(fileUrl);
    }

    private void addDownloadingTask(String fileUrl){
        if (tasksDownloading == null){
            tasksDownloading = new ArrayList<String>();
        }
        if (!tasksDownloading.contains(fileUrl)){
            synchronized (this){
                tasksDownloading.add(fileUrl);
            }
        }
    }

    private boolean removeDownloading(String fileUrl){
        if (tasksDownloading != null){
            synchronized (this){
                return tasksDownloading.remove(fileUrl);
            }
        }
        return false;
    }

    private boolean downloadCourseFile(String fileUrl, String shortname, String versionID){

        File downloadedFile = null;
        try {

            HTTPConnectionUtils client = new HTTPConnectionUtils(this);
            String downloadUrl =  client.createUrlWithCredentials(fileUrl);
            String v = "0";
            try {
                v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }

            URL url = new URL(downloadUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty(CoreProtocolPNames.USER_AGENT, MobileLearning.USER_AGENT + v);
            Log.d(TAG, CoreProtocolPNames.USER_AGENT + ":" + MobileLearning.USER_AGENT + v);
            connection.setDoOutput(true);
            connection.connect();
            connection.setConnectTimeout(Integer.parseInt(prefs.getString(PrefsActivity.PREF_SERVER_TIMEOUT_CONN,
                    this.getString(R.string.prefServerTimeoutConnection))));
            connection.setReadTimeout(Integer.parseInt(prefs.getString(PrefsActivity.PREF_SERVER_TIMEOUT_RESP,
                    this.getString(R.string.prefServerTimeoutResponse))));

            long fileLength = connection.getContentLength();
            long availableStorage = FileUtils.getAvailableStorageSize(this);

            if (fileLength >= availableStorage){
                sendBroadcast(fileUrl, ACTION_FAILED, this.getString(R.string.error_insufficient_storage_available));
                removeDownloading(fileUrl);
                return false;
            }

            String localFileName = getLocalFilename(shortname, versionID);
            downloadedFile = new File(FileUtils.getDownloadPath(this),localFileName);
            FileOutputStream f = new FileOutputStream(downloadedFile);
            InputStream in = connection.getInputStream();

            byte[] buffer = new byte[8192];
            int len1;
            long total = 0;
            int previousProgress = 0, progress = 0;
            while ((len1 = in.read(buffer)) > 0) {
                //If received a cancel action while downloading, stop it
                if (isCancelled(fileUrl)) {
                    Log.d(TAG, "Course " + localFileName + " cancelled while downloading. Deleting temp file...");
                    deleteFile(downloadedFile);
                    removeCancelled(fileUrl);
                    removeDownloading(fileUrl);
                    return false;
                }

                total += len1;
                progress = (int)((total*100)/fileLength);
                if ( (progress > 0) && (progress > previousProgress)){
                    sendBroadcast(fileUrl, ACTION_DOWNLOAD, ""+progress);
                    previousProgress = progress;
                }
                f.write(buffer, 0, len1);
            }
            f.close();

        } catch (MalformedURLException e) {
            logAndNotifyError(fileUrl, e);
            return false;
        } catch (ProtocolException e) {
            this.deleteFile(downloadedFile);
            logAndNotifyError(fileUrl, e);
            return false;
        } catch (IOException e) {
            this.deleteFile(downloadedFile);
            logAndNotifyError(fileUrl, e);
            return false;
        }

        Log.d(TAG, fileUrl + " succesfully downloaded");
        removeDownloading(fileUrl);
        sendBroadcast(fileUrl, ACTION_INSTALL, "0");
        return true;
    }

    private String getLocalFilename(String shortname, String versionID){
        return shortname+"-"+String.format("%.0f",versionID)+".zip";
    }

    private void deleteFile(File file){
        if ((file != null) && file.exists() && !file.isDirectory()){
            file.delete();
        }
    }
}
