package me.saket.dank.ui.subreddit.events;

import android.view.View;

import com.google.auto.value.AutoValue;

import net.dean.jraw.models.Submission;

import me.saket.dank.ui.UiEvent;

@AutoValue
public abstract class SubredditSubmissionClickEvent implements UiEvent {

  public abstract Submission submission();

  public abstract View itemView();

  public abstract long itemId();

  public static SubredditSubmissionClickEvent create(Submission submission, View itemView, long itemId) {
    return new AutoValue_SubredditSubmissionClickEvent(submission, itemView, itemId);
  }
}
