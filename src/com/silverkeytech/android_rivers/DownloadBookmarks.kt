/*
Android Rivers is an app to read and discover news using RiverJs, RSS and OPML format.
Copyright (C) 2012 Dody Gunawinata (dodyg@silverkeytech.com)

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>
*/
package com.silverkeytech.android_rivers

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.DialogInterface
import android.os.AsyncTask
import android.util.Log
import com.github.kevinsawicki.http.HttpRequest.HttpRequestException
import com.silverkeytech.android_rivers.outliner.transformXmlToOpml
import com.silverkeytech.android_rivers.outlines.Opml

public class DownloadBookmarks(it: Context?, ignoreCache: Boolean): AsyncTask<String, Int, Result<Opml>>(){
    class object {
        public val TAG: String = javaClass<DownloadBookmarks>().getSimpleName()
    }

    var dialog: ProgressDialog = ProgressDialog(it)
    var context: Activity = it!! as Activity
    val ignoreCache: Boolean = ignoreCache

    protected override fun onPreExecute() {
        dialog.setMessage(context.getString(R.string.please_wait_while_downloading_news_rivers_list))
        dialog.setIndeterminate(true)
        dialog.setCancelable(false)
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, context.getString(R.string.cancel), object : DialogInterface.OnClickListener{

            public override fun onClick(p0: DialogInterface?, p1: Int) {
                p0!!.dismiss()
                this@DownloadBookmarks.cancel(true)
            }
        })
        dialog.show()
    }

    protected override fun doInBackground(vararg url: String?): Result<Opml>? {
        try{
            val cache = context.getMain().getRiverBookmarksCache()

            if (cache != null && !ignoreCache){
                Log.d(TAG, "Cache is hit for subscription list")
                return Result.right(cache)
            }
            else {
                Log.d(TAG, "Cache is missed for subscription list")

                var req: String?
                try{
                    req = httpGet(url[0]!!).body()
                }
                catch(e: HttpRequestException){
                    var ex = e.getCause()
                    return Result.wrong(ex)
                }

                val opml = transformXmlToOpml(req)

                if(opml.isTrue()){
                    context.getMain().setRiverBookmarksCache(opml.value!!)
                }

                return opml
            }
        } catch(e: Exception){
            return Result.wrong(e)
        }
    }

    var rawCallback: ((Result<Opml>) -> Unit)? = null

    public fun executeOnComplete(callback: (Result<Opml>) -> Unit): DownloadBookmarks {
        rawCallback = callback
        return this
    }

    protected override fun onPostExecute(result: Result<Opml>?) {
        dialog.dismiss()

        if (result == null){
            context.toastee("Sorry, we cannot handle this subscription download because operation is cancelled", Duration.AVERAGE)
        }
        else{
            if (result.isFalse()){
                val error = ConnectivityErrorMessage(
                        timeoutException = "Sorry, we cannot download this subscription list. The subscription site might be down",
                        socketException = "Sorry, we cannot download this subscription list. Please check your Internet connection, it might be down",
                        otherException = "Sorry, we cannot download this subscription list for the following technical reason : ${result.exception.toString()}"
                )

                context.handleConnectivityError(result.exception, error)

            } else {
                if (rawCallback != null)
                    rawCallback!!(result)
            }
        }
    }
}
