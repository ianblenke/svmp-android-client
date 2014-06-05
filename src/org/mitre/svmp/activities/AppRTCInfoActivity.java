/*
 * Copyright (c) 2013 The MITRE Corporation, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this work except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Derived from AppRTCActivity from the libjingle / webrtc AppRTCDemo
// example application distributed under the following license.
/*
 * libjingle
 * Copyright 2013, Google Inc.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *  3. The name of the author may not be used to endorse or promote products
 *     derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
 * EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.mitre.svmp.activities;

import android.content.Intent;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import com.google.protobuf.ByteString;
import org.mitre.svmp.common.AppInfo;
import org.mitre.svmp.protocol.SVMPProtocol;
import org.mitre.svmp.protocol.SVMPProtocol.Response;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;

/**
 * Main Activity of the SVMP Android client application.
 */
public class AppRTCInfoActivity extends AppRTCActivity {
    private static final String TAG = AppRTCInfoActivity.class.getName();

    private boolean fullRefresh;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        this.fullRefresh = intent.getBooleanExtra("fullRefresh", false);
    }

    // MessageHandler interface method
    // Called when the client connection is established
    @Override
    public void onOpen() {
        super.onOpen();

        // send a Request message with our current list of apps
        sendAppsRequest();
    }

    // MessageHandler interface method
    // Called when a message is sent from the server, and the SessionService doesn't consume it
    public boolean onMessage(Response data) {
        switch (data.getType()) {
            case APPS:
                handleAppsResponse(data);
                break;
            default:
                // any messages we don't understand, pass to our parent for processing
                super.onMessage(data);
        }
        return true;
    }

    // sends a Request - containing our current apps and their info - to the VM to see what needs to be
    // added, updated, or removed
    private void sendAppsRequest() {
        SVMPProtocol.AppsRequest.Builder arBuilder = SVMPProtocol.AppsRequest.newBuilder();
        arBuilder.setType(SVMPProtocol.AppsRequest.AppsRequestType.REFRESH);
        // set screen density for AppsRequest
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        arBuilder.setScreenDensity(metrics.densityDpi);

        if (!fullRefresh) {
            // if we're not doing a full refresh, loop through current app info list and construct an AppsRequest
            List<AppInfo> appInfoList = dbHandler.getAppInfoList_All(connectionInfo.getConnectionID());
            for (AppInfo appInfo : appInfoList) {
                SVMPProtocol.AppInfo.Builder aiBuilder = SVMPProtocol.AppInfo.newBuilder();
                aiBuilder.setAppName(appInfo.getAppName());
                aiBuilder.setPkgName(appInfo.getPackageName());
                aiBuilder.setIconHash(convertIconHash(appInfo));
                arBuilder.addCurrent(aiBuilder);
            }
        }

        // package AppsRequest into a Request and send it
        SVMPProtocol.Request.Builder rBuilder = SVMPProtocol.Request.newBuilder();
        rBuilder.setType(SVMPProtocol.Request.RequestType.APPS);
        rBuilder.setApps(arBuilder);
        sendMessage(rBuilder.build());
    }

    // converts an app's icon hash to a ByteString; if the hash is null, returns an empty ByteString
    private ByteString convertIconHash(AppInfo appInfo) {
        ByteString value = ByteString.EMPTY;
        byte[] iconHash = appInfo.getIconHash();
        if (iconHash != null)
            value = ByteString.copyFrom(iconHash);
        return value;
    }

    // handles Response from VM to see what apps need to be added, updated, or removed
    private void handleAppsResponse(Response response) {
        Log.v(TAG, "Received APPS response");
        SVMPProtocol.AppsResponse appsResponse = response.getApps();
        int connectionID = connectionInfo.getConnectionID();

        // get the existing (outdated) list of apps
        List<AppInfo> oldApps = dbHandler.getAppInfoList_All(connectionID);
        // create a HashMap for looping through
        HashMap<String, AppInfo> oldAppsMap = new HashMap<String, AppInfo>();
        for (AppInfo appInfo : oldApps)
            oldAppsMap.put(appInfo.getPackageName(), appInfo);

        // remove apps that don't exist on the VM anymore
        if (fullRefresh) {
            // if this was a full refresh, delete all existing apps (they will be re-added)
            dbHandler.deleteAllAppInfos(connectionID);
        }
        else {
            // this was a partial refresh, only delete apps that the VM has specified
            List<String> removedApps = appsResponse.getRemovedList();
            for (String packageName : removedApps) {
                if (oldAppsMap.containsKey(packageName))
                    dbHandler.deleteAppInfo(oldAppsMap.get(packageName));
            }
        }

        // loop through the list of new AppInfos that were returned from the VM and add them
        List<SVMPProtocol.AppInfo> newApps = appsResponse.getNewList();
        List<SVMPProtocol.AppInfo> updatedApps = appsResponse.getUpdatedList();
        for (SVMPProtocol.AppInfo appInfo : newApps) {
            String packageName = appInfo.getPkgName(),
                    appName = appInfo.getAppName();
            byte[] icon = appInfo.hasIcon() ? appInfo.getIcon().toByteArray() : null,
                    iconHash = getIconHash(icon);
            boolean favorite = false;
            AppInfo newAppInfo = new AppInfo(connectionID, packageName, appName, false, icon, iconHash);
            dbHandler.insertAppInfo(newAppInfo);
        }

        // loop through the list of updated AppInfos that were returned from the VM and update them
        for (SVMPProtocol.AppInfo appInfo : updatedApps) {
            String packageName = appInfo.getPkgName(),
                    appName = appInfo.getAppName();
            boolean favorite = oldAppsMap.get(packageName).isFavorite();
            byte[] icon = appInfo.hasIcon() ? appInfo.getIcon().toByteArray() : null,
                    iconHash = getIconHash(icon);
            AppInfo newAppInfo = new AppInfo(connectionID, packageName, appName, favorite, icon, iconHash);
            dbHandler.updateAppInfo(newAppInfo);
        }

        Log.v(TAG, "Successfully processed APPS response");
        setResult(SvmpActivity.RESULT_REPOPULATE);
        disconnectAndExit();
    }

    // converts an app's icon into a 20-byte hash, returns null if the icon is null
    private byte[] getIconHash(byte[] icon) {
        byte[] value = null;
        if (icon != null) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                value = md.digest(icon);
            } catch (NoSuchAlgorithmException e) {
                Log.e(TAG, "convertIconHash failed: " + e.getMessage());
            }
        }
        return value;
    }
}
