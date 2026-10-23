package com.claxe.sorune

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** The home screen search bar: opens Sorune ready to type (the glasses: a private tab). */
class SearchWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_search)
            views.setOnClickPendingIntent(R.id.widgetRoot, open(context, MainActivity.ACTION_SEARCH, 1))
            views.setOnClickPendingIntent(R.id.widgetPrivate, open(context, MainActivity.ACTION_PRIVATE, 2))
            manager.updateAppWidget(id, views)
        }
    }

    private fun open(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getActivity(
            context, code,
            Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
