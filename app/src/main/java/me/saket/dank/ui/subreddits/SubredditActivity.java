package me.saket.dank.ui.subreddits;

import static io.reactivex.android.schedulers.AndroidSchedulers.mainThread;
import static io.reactivex.schedulers.Schedulers.io;
import static io.reactivex.schedulers.Schedulers.single;
import static me.saket.dank.utils.RxUtils.applySchedulers;
import static me.saket.dank.utils.RxUtils.doNothingCompletable;
import static me.saket.dank.utils.RxUtils.logError;
import static me.saket.dank.utils.Views.executeOnMeasure;
import static me.saket.dank.utils.Views.setMarginTop;
import static me.saket.dank.utils.Views.setPaddingTop;
import static me.saket.dank.utils.Views.touchLiesOn;

import android.animation.LayoutTransition;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.widget.DefaultItemAnimator;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.github.zagum.expandicon.ExpandIconView;
import com.jakewharton.rxbinding2.internal.Notification;
import com.jakewharton.rxrelay2.BehaviorRelay;
import com.jakewharton.rxrelay2.PublishRelay;
import com.jakewharton.rxrelay2.Relay;

import net.dean.jraw.models.Submission;
import net.dean.jraw.models.Subreddit;
import net.dean.jraw.paginators.Sorting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import me.saket.dank.DatabaseCacheRecyclerJobService;
import me.saket.dank.R;
import me.saket.dank.data.DankRedditClient;
import me.saket.dank.data.ErrorResolver;
import me.saket.dank.data.InfiniteScrollFooter;
import me.saket.dank.data.InfiniteScrollHeader;
import me.saket.dank.data.OnLoginRequireListener;
import me.saket.dank.data.SubredditSubscriptionManager;
import me.saket.dank.data.UserPreferences;
import me.saket.dank.data.links.RedditSubredditLink;
import me.saket.dank.di.Dank;
import me.saket.dank.ui.DankPullCollapsibleActivity;
import me.saket.dank.ui.authentication.LoginActivity;
import me.saket.dank.ui.preferences.UserPreferencesActivity;
import me.saket.dank.ui.submission.CachedSubmissionFolder;
import me.saket.dank.ui.submission.SortingAndTimePeriod;
import me.saket.dank.ui.submission.SubmissionPageLayout;
import me.saket.dank.ui.submission.SubmissionRepository;
import me.saket.dank.ui.submission.adapter.SubmissionCommentsHeader;
import me.saket.dank.ui.user.UserSessionRepository;
import me.saket.dank.utils.DankSubmissionRequest;
import me.saket.dank.utils.Keyboards;
import me.saket.dank.utils.Optional;
import me.saket.dank.utils.Pair;
import me.saket.dank.widgets.DankToolbar;
import me.saket.dank.widgets.EmptyStateView;
import me.saket.dank.widgets.ErrorStateView;
import me.saket.dank.widgets.InboxUI.InboxRecyclerView;
import me.saket.dank.widgets.InboxUI.IndependentExpandablePageLayout;
import me.saket.dank.widgets.InboxUI.RxExpandablePage;
import me.saket.dank.widgets.ToolbarExpandableSheet;
import me.saket.dank.widgets.swipe.RecyclerSwipeListener;
import timber.log.Timber;

public class SubredditActivity extends DankPullCollapsibleActivity implements SubmissionPageLayout.Callbacks,
    NewSubredditSubscriptionDialog.Callback
{

  protected static final String KEY_INITIAL_SUBREDDIT_LINK = "initialSubredditLink";
  private static final String KEY_ACTIVE_SUBREDDIT = "activeSubreddit";
  private static final String KEY_IS_SUBREDDIT_PICKER_SHEET_VISIBLE = "isSubredditPickerVisible";
  private static final String KEY_IS_USER_PROFILE_SHEET_VISIBLE = "isUserProfileSheetVisible";
  private static final String KEY_SORTING_AND_TIME_PERIOD = "sortingAndTimePeriod";

  @BindView(R.id.subreddit_root) IndependentExpandablePageLayout contentPage;
  @BindView(R.id.subreddit_submission_page) SubmissionPageLayout submissionPageLayout;
  @BindView(R.id.toolbar) DankToolbar toolbar;
  @BindView(R.id.subreddit_toolbar_close) View toolbarCloseButton;
  @BindView(R.id.subreddit_toolbar_title) TextView toolbarTitleView;
  @BindView(R.id.subreddit_toolbar_title_arrow) ExpandIconView toolbarTitleArrowView;
  @BindView(R.id.subreddit_toolbar_title_container) ViewGroup toolbarTitleContainer;
  @BindView(R.id.subreddit_toolbar_container) ViewGroup toolbarContainer;
  @BindView(R.id.subreddit_sorting_mode_container) ViewGroup sortingModeContainer;
  @BindView(R.id.subreddit_sorting_mode) Button sortingModeButton;
  @BindView(R.id.subreddit_subscribe) Button subscribeButton;
  @BindView(R.id.subreddit_submission_list) InboxRecyclerView submissionList;
  @BindView(R.id.subreddit_toolbar_expandable_sheet) ToolbarExpandableSheet toolbarSheet;
  @BindView(R.id.subreddit_progress) View fullscreenProgressView;
  @BindView(R.id.subreddit_submission_emptyState) EmptyStateView emptyStateView;
  @BindView(R.id.subreddit_submission_errorState) ErrorStateView firstLoadErrorStateView;

  @Inject SubmissionRepository submissionRepository;
  @Inject ErrorResolver errorResolver;
  @Inject CachePreFiller cachePreFiller;
  @Inject SubredditSubscriptionManager subscriptionManager;
  @Inject UserPreferences userPrefs;
  @Inject UserSessionRepository userSessionRepository;

  private BehaviorRelay<String> subredditChangesStream = BehaviorRelay.create();
  private BehaviorRelay<SortingAndTimePeriod> sortingChangesStream = BehaviorRelay.create();
  private Relay<Object> forceRefreshRequestStream = PublishRelay.create();
  private SubmissionsAdapter submissionsAdapter;
  private SubmissionPageAnimationOptimizer submissionPageAnimationOptimizer = new SubmissionPageAnimationOptimizer();

  protected static void addStartExtrasToIntent(RedditSubredditLink subredditLink, @Nullable Rect expandFromShape, Intent intent) {
    intent.putExtra(KEY_INITIAL_SUBREDDIT_LINK, subredditLink);
    intent.putExtra(KEY_EXPAND_FROM_SHAPE, expandFromShape);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    Dank.dependencyInjector().inject(this);
    boolean isPullCollapsible = !isTaskRoot();
    setPullToCollapseEnabled(isPullCollapsible);

    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_subreddit);
    ButterKnife.bind(this);
    setupContentExpandablePage(contentPage);

    // Add top-margin to make room for the status bar.
    executeOnMeasure(toolbar, () -> setMarginTop(sortingModeContainer, toolbar.getHeight()));
    executeOnMeasure(sortingModeContainer, () -> setPaddingTop(submissionList, sortingModeContainer.getHeight() + toolbar.getHeight()));

    findAndSetupToolbar();
    getSupportActionBar().setDisplayShowTitleEnabled(false);

    if (isPullCollapsible) {
      expandFrom(getIntent().getParcelableExtra(KEY_EXPAND_FROM_SHAPE));
      toolbarCloseButton.setVisibility(View.VISIBLE);
      toolbarCloseButton.setOnClickListener(o -> finish());
    }
    contentPage.setNestedExpandablePage(submissionPageLayout);
  }

  @Override
  protected void onPostCreate(@Nullable Bundle savedState) {
    super.onPostCreate(savedState);

    setupSubmissionList(savedState);
    setupSubmissionPage();
    setupToolbarSheet();

    // Restore state of subreddit picker sheet / user profile sheet.
    if (savedState != null) {
      if (savedState.getBoolean(KEY_IS_SUBREDDIT_PICKER_SHEET_VISIBLE)) {
        showSubredditPickerSheet();
      } else if (savedState.getBoolean(KEY_IS_USER_PROFILE_SHEET_VISIBLE)) {
        showUserProfileSheet();
      }
    }

    // Animate changes in sorting button's width on text change.
    LayoutTransition layoutTransition = sortingModeContainer.getLayoutTransition();
    layoutTransition.enableTransitionType(LayoutTransition.CHANGING);

    if (savedState != null && savedState.containsKey(KEY_SORTING_AND_TIME_PERIOD)) {
      //noinspection ConstantConditions
      sortingChangesStream.accept(savedState.getParcelable(KEY_SORTING_AND_TIME_PERIOD));
    } else {
      sortingChangesStream.accept(SortingAndTimePeriod.create(Sorting.HOT));
    }
    sortingChangesStream
        .takeUntil(lifecycle().onDestroy())
        .subscribe(sortingAndTimePeriod -> {
          if (sortingAndTimePeriod.sortOrder().requiresTimePeriod()) {
            sortingModeButton.setText(getString(
                R.string.subreddit_sorting_mode_with_time_period,
                getString(sortingAndTimePeriod.getSortingDisplayTextRes()),
                getString(sortingAndTimePeriod.getTimePeriodDisplayTextRes())
            ));
          } else {
            sortingModeButton.setText(getString(R.string.subreddit_sorting_mode, getString(sortingAndTimePeriod.getSortingDisplayTextRes())));
          }
        });

    emptyStateView.setEmoji(R.string.subreddit_empty_state_title);
    emptyStateView.setMessage(R.string.subreddit_empty_state_message);

    // Toggle the subscribe button's visibility.
    subredditChangesStream
        .switchMap(subredditName -> subscriptionManager.isSubscribed(subredditName))
        .compose(applySchedulers())
        .startWith(Boolean.FALSE)
        .onErrorResumeNext(error -> {
          logError("Couldn't get subscribed status for %s", subredditChangesStream.getValue()).accept(error);
          return Observable.just(false);
        })
        .takeUntil(lifecycle().onDestroy())
        .subscribe(isSubscribed -> subscribeButton.setVisibility(isSubscribed ? View.GONE : View.VISIBLE));

    userSessionRepository.streamFutureLogInEvents()
        .takeUntil(lifecycle().onDestroy())
        .subscribe(session -> handleOnUserLogIn());
  }

  @Override
  public void onDestroy() {
    super.onDestroy();

    // Recycle cached rows in DB.
    DatabaseCacheRecyclerJobService.schedule(this);
  }

  @OnClick(R.id.subreddit_subscribe)
  public void onClickSubscribeToSubreddit() {
    String subredditName = subredditChangesStream.getValue();
    subscribeButton.setVisibility(View.GONE);

    // Intentionally not unsubscribing from this API call on Activity destroy.
    // We'll treat it as a fire-n-forget call and let them run even when this Activity exits.
    Dank.reddit().findSubreddit(subredditName)
        .flatMapCompletable(subreddit -> subscriptionManager.subscribe(subreddit))
        .subscribeOn(io())
        .subscribe(doNothingCompletable(), logError("Couldn't subscribe to %s", subredditName));
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    submissionList.handleOnSaveInstance(outState);
    if (subredditChangesStream.hasValue()) {
      outState.putString(KEY_ACTIVE_SUBREDDIT, subredditChangesStream.getValue());
    }
    if (sortingChangesStream.hasValue()) {
      outState.putParcelable(KEY_SORTING_AND_TIME_PERIOD, sortingChangesStream.getValue());
    }
    outState.putBoolean(KEY_IS_SUBREDDIT_PICKER_SHEET_VISIBLE, isSubredditPickerVisible());
    outState.putBoolean(KEY_IS_USER_PROFILE_SHEET_VISIBLE, isUserProfileSheetVisible());
    super.onSaveInstanceState(outState);
  }

  @Override
  public void setTitle(CharSequence subredditName) {
    boolean isFrontpage = subscriptionManager.isFrontpage(subredditName.toString());
    toolbarTitleView.setText(isFrontpage ? getString(R.string.app_name) : subredditName);
  }

  private void setupSubmissionPage() {
    contentPage.setPullToCollapseIntercepter((event, downX, downY, upwardPagePull) -> {
      if (touchLiesOn(toolbarContainer, downX, downY)) {
        if (touchLiesOn(toolbarSheet, downX, downY) && isSubredditPickerVisible()) {
          boolean intercepted = findSubredditPickerSheet().shouldInterceptPullToCollapse(downX, downY);
          if (intercepted) {
            return true;
          }
        }
        return false;
      }
      //noinspection SimplifiableIfStatement
      if (touchLiesOn(submissionList, downX, downY)) {
        return submissionList.canScrollVertically(upwardPagePull ? 1 : -1);
      }
      return false;
    });
  }

  private void setupToolbarSheet() {
    toolbarSheet.hideOnOutsideClick(submissionList);
    toolbarSheet.setStateChangeListener(state -> {
      switch (state) {
        case EXPANDING:
          if (isSubredditPickerVisible()) {
            // When subreddit picker is showing, we'll show a "configure subreddits" button in the toolbar.
            invalidateOptionsMenu();

          } else if (isUserProfileSheetVisible()) {
            setTitle(getString(R.string.user_name_u_prefix, userSessionRepository.loggedInUserName()));
          }

          toolbarTitleArrowView.setState(ExpandIconView.LESS, true);
          break;

        case EXPANDED:
          break;

        case COLLAPSING:
          if (isSubredditPickerVisible()) {
            Keyboards.hide(this, toolbarSheet);

          } else if (isUserProfileSheetVisible()) {
            // This will update the title.
            setTitle(subredditChangesStream.getValue());
          }
          toolbarTitleArrowView.setState(ExpandIconView.MORE, true);
          break;

        case COLLAPSED:
          toolbarSheet.removeAllViews();
          toolbarSheet.collapse();
          break;
      }
    });
  }

// ======== SUBMISSION LIST ======== //

  private void setupSubmissionList(Bundle savedState) {
    submissionList.setLayoutManager(submissionList.createLayoutManager());
    submissionList.setItemAnimator(new DefaultItemAnimator());
    submissionList.setExpandablePage(submissionPageLayout, toolbarContainer);

    // Swipe gestures.
    OnLoginRequireListener onLoginRequireListener = () -> LoginActivity.intent(this);
    SubmissionSwipeActionsProvider swipeActionsProvider = new SubmissionSwipeActionsProvider(
        submissionRepository,
        Dank.voting(),
        userSessionRepository,
        onLoginRequireListener
    );
    submissionList.addOnItemTouchListener(new RecyclerSwipeListener(submissionList));

    submissionsAdapter = new SubmissionsAdapter(Dank.voting(), userPrefs, swipeActionsProvider);
    submissionsAdapter.setOnItemClickListener((submission, submissionItemView, submissionId) -> {
      DankSubmissionRequest submissionRequest = DankSubmissionRequest.builder(submission.getId())
          .commentSort(submission.getSuggestedSort() != null ? submission.getSuggestedSort() : DankRedditClient.DEFAULT_COMMENT_SORT)
          .build();

      long delay = submissionPageAnimationOptimizer.shouldDelayLoad(submission)
          ? submissionPageLayout.getAnimationDurationMillis()
          : 0;

      Single.timer(delay, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread())
          .takeUntil(lifecycle().onDestroy().ignoreElements())
          .subscribe(o -> {
            submissionPageLayout.populateUi(Optional.of(submission), submissionRequest);
            submissionPageAnimationOptimizer.trackSubmissionOpened(submission);
          });

      submissionPageLayout.post(() ->
          Observable.timer(100 + delay / 2, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread())
              .takeUntil(lifecycle().onDestroy())
              .subscribe(o -> submissionList.expandItem(submissionList.indexOfChild(submissionItemView), submissionId))
      );
    });
    submissionList.setAdapter(submissionsAdapter);

    subredditChangesStream
        .observeOn(mainThread())
        .takeUntil(lifecycle().onDestroy())
        .subscribe(subreddit -> setTitle(subreddit));

    loadSubmissions();

    // Get frontpage (or retained subreddit's) submissions.
    if (savedState != null && savedState.containsKey(KEY_ACTIVE_SUBREDDIT)) {
      String retainedSub = savedState.getString(KEY_ACTIVE_SUBREDDIT);
      //noinspection ConstantConditions
      subredditChangesStream.accept(retainedSub);
    } else if (getIntent().hasExtra(KEY_INITIAL_SUBREDDIT_LINK)) {
      String requestedSub = ((RedditSubredditLink) getIntent().getParcelableExtra(KEY_INITIAL_SUBREDDIT_LINK)).name();
      subredditChangesStream.accept(requestedSub);
    } else {
      subredditChangesStream.accept(subscriptionManager.defaultSubreddit());
    }

    if (savedState != null) {
      submissionList.handleOnRestoreInstanceState(savedState);
    }
  }

  private void loadSubmissions() {
    // TODO: Handle full-screen error
    // TODO: Refresh submission on start.

    Observable<CachedSubmissionFolder> submissionFolderStream = Observable.combineLatest(
        subredditChangesStream,
        sortingChangesStream,
        CachedSubmissionFolder::create
    );

    Relay<NetworkCallStatus> paginationStatusStream = BehaviorRelay.create();
    Relay<NetworkCallStatus> refreshStatusStream = BehaviorRelay.create();
    Relay<List<Submission>> cachedSubmissionStream = BehaviorRelay.create();

    // Pagination.
    // Note: We're also treating initial load of items as pagination.
    submissionFolderStream
        .observeOn(mainThread())
        //.doOnNext(folder -> Timber.d("-------------------------------"))
        //.doOnNext(folder -> Timber.i("%s", folder))
        .takeUntil(lifecycle().onDestroy())
        .switchMap(folder -> InfiniteScroller.streamPagingRequests(submissionList)
            .mergeWith(submissionRepository.submissionCount(folder).take(1).filter(count -> count == 0)  /* Force initial load */)
            .mergeWith(forceRefreshRequestStream)
            //.doOnNext(o -> Timber.d("Loading more…"))
            .flatMap(o -> submissionRepository.loadAndSaveMoreSubmissions(folder))
        )
        .subscribe(paginationStatusStream);

    // DB subscription.
    // We suspend the listener while a submission is active so that this list doesn't get updated in background.
    RxExpandablePage.collapses(submissionPageLayout)
        .switchMap(o -> submissionFolderStream
            .switchMap(folder -> submissionRepository.submissions(folder).subscribeOn(io()))
            .takeUntil(RxExpandablePage.preExpansions(submissionPageLayout))
        )
        .takeUntil(lifecycle().onDestroy())
        .subscribe(cachedSubmissionStream);

    // TODO:
    // Refresh.
//    forceRefreshRequestStream
//        .withLatestFrom(submissionFolderStream, (o, folder) -> folder)
//        .observeOn(io())
//        .switchMap(folder -> submissionRepository.loadAndSaveMoreSubmissions(folder))
//        .takeUntil(lifecycle().onDestroy())
//        .subscribe(refreshStatusStream);

    // Adapter data-set.
    Observable<SubmissionListUiModel> submissionListUiModelStream = Observable.combineLatest(
        cachedSubmissionStream.startWith(Collections.<Submission>emptyList()),
        Observable.combineLatest(
            paginationStatusStream.startWith(NetworkCallStatus.createIdle()),
            refreshStatusStream.startWith(NetworkCallStatus.createIdle()),
            (pagination, refresh) -> Pair.create(pagination, refresh)
        ),
        (submissions, pair) -> SubmissionListUiModel.create(submissions, pair.first(), pair.second())
    );

    submissionListUiModelStream
        .observeOn(mainThread())
        .map(uiModel -> {
          List<Object> adapterDataset = new ArrayList<>(uiModel.submissions().size() + 2);
          if (uiModel.refreshStatus().state() == NetworkCallStatus.State.IN_FLIGHT) {
            adapterDataset.add(InfiniteScrollHeader.createProgress(R.string.subreddit_refreshing_submissions));

          } else if (uiModel.refreshStatus().state() == NetworkCallStatus.State.FAILED) {
            // TODO: Retry click listener.
            adapterDataset.add(InfiniteScrollHeader.createError(R.string.subreddit_error_failed_to_refresh_submissions));

          } else if (uiModel.refreshStatus().state() != NetworkCallStatus.State.IDLE) {
            throw new AssertionError();
          }

          adapterDataset.addAll(uiModel.submissions());

          NetworkCallStatus.State paginationState = uiModel.paginationStatus().state();
          //Timber.i("---------------------");
          //Timber.i("Cached submissions: %s", uiModel.submissions().size());
          //Timber.i("Pagination state: %s", paginationState);
          //Timber.i("Refresh state: %s", uiModel.refreshStatus().state());

          if (uiModel.submissions().isEmpty() && paginationState == NetworkCallStatus.State.IN_FLIGHT) {
            fullscreenProgressView.setVisibility(View.VISIBLE);
          } else {
            fullscreenProgressView.setVisibility(View.GONE);
          }

          if (!uiModel.submissions().isEmpty()) {
            if (paginationState == NetworkCallStatus.State.IN_FLIGHT) {
              adapterDataset.add(InfiniteScrollFooter.createProgress());

            } else if (paginationState == NetworkCallStatus.State.FAILED) {
              // TODO: Retry click listener.
              adapterDataset.add(InfiniteScrollFooter.createError(R.string.subreddit_error_failed_to_load_more_submissions));

            } else if (paginationState != NetworkCallStatus.State.IDLE) {
              throw new AssertionError();
            }
          }
          return Collections.unmodifiableList(adapterDataset);
        })
        .subscribe(adapterDataset -> {
          // TODO: 17/09/17 Use DiffUtils.
          submissionsAdapter.updateDataAndNotifyDatasetChanged(adapterDataset);
        });

    // Cache pre-fill.
    int displayWidth = getResources().getDisplayMetrics().widthPixels;
    int submissionAlbumLinkThumbnailWidth = SubmissionCommentsHeader.getWidthForAlbumContentLinkThumbnail(this);
    cachedSubmissionStream
        .withLatestFrom(submissionFolderStream, Pair::create)
        .observeOn(single())
        .flatMap(pair -> subscriptionManager.isSubscribed(pair.second().subredditName())
            .flatMap(isSubscribed -> isSubscribed ? Observable.just(pair.first()) : Observable.never())
        )
        .switchMap(cachedSubmissions -> cachePreFiller.preFillInParallelThreads(cachedSubmissions, displayWidth, submissionAlbumLinkThumbnailWidth)
            .toObservable()
        )
        .takeUntil(lifecycle().onDestroy())
        .subscribe();
  }

  @Override
  public SubmissionPageAnimationOptimizer submissionPageAnimationOptimizer() {
    return submissionPageAnimationOptimizer;
  }

// ======== SORTING MODE ======== //

  @OnClick(R.id.subreddit_sorting_mode)
  public void onClickSortingMode(Button sortingModeButton) {
    SubmissionsSortingModePopupMenu sortingPopupMenu = new SubmissionsSortingModePopupMenu(this, sortingModeButton);
    sortingPopupMenu.inflate(R.menu.menu_submission_sorting_mode);
    sortingPopupMenu.highlightActiveSortingAndTImePeriod(sortingChangesStream.getValue());
    sortingPopupMenu.setOnSortingModeSelectListener(sortingAndTimePeriod -> sortingChangesStream.accept(sortingAndTimePeriod));
    sortingPopupMenu.show();
  }

// ======== NAVIGATION ======== //

  @Override
  public void onClickSubmissionToolbarUp() {
    submissionList.collapse();
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.menu_subreddit, menu);
    return super.onCreateOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      case R.id.action_refresh_submissions:
        subredditChangesStream
            .take(1)
            .observeOn(io())
            .doOnNext(o -> Timber.i("---------------------------"))
            .flatMapCompletable(subreddit -> submissionRepository.clearCachedSubmissionLists(subreddit))
            .observeOn(mainThread())
            .subscribe(() -> forceRefreshRequestStream.accept(Notification.INSTANCE));
        return true;

      case R.id.action_user_profile:
        if (userSessionRepository.isUserLoggedIn()) {
          showUserProfileSheet();
        } else {
          startActivity(LoginActivity.intent(this));
        }
        return true;

      case R.id.action_preferences:
        UserPreferencesActivity.start(this);
        //ComposeReplyActivity.start(this, ComposeStartOptions.builder()
        //    .secondPartyName("Test")
        //    .parentContribution(ContributionFullNameWrapper.create("Poop"))
        //    .build()
        //);
        return true;

      default:
        return super.onOptionsItemSelected(item);
    }
  }

  @OnClick(R.id.subreddit_toolbar_title)
  void onClickToolbarTitle() {
    if (isUserProfileSheetVisible() || isSubredditPickerVisible()) {
      toolbarSheet.collapse();
    } else {
      showSubredditPickerSheet();
    }
  }

// ======== SUBREDDIT PICKER SHEET ======== //

  void showSubredditPickerSheet() {
    SubredditPickerSheetView pickerSheet = SubredditPickerSheetView.showIn(toolbarSheet, contentPage);
    pickerSheet.post(() -> toolbarSheet.expand());

    pickerSheet.setCallbacks(new SubredditPickerSheetView.Callbacks() {
      @Override
      public void onSelectSubreddit(String subredditName) {
        toolbarSheet.collapse();
        if (!subredditName.equalsIgnoreCase(subredditChangesStream.getValue())) {
          subredditChangesStream.accept(subredditName);
        }
      }

      @Override
      public void onClickAddNewSubreddit() {
        NewSubredditSubscriptionDialog.show(getSupportFragmentManager());
      }

      @Override
      public void onSubredditsChanged() {
        // Refresh the submissions if the frontpage was active.
        if (subscriptionManager.isFrontpage(subredditChangesStream.getValue())) {
          subredditChangesStream.accept(subredditChangesStream.getValue());
        }
      }
    });
  }

  @Override
  public void onEnterNewSubredditForSubscription(Subreddit newSubreddit) {
    if (isSubredditPickerVisible()) {
      findSubredditPickerSheet().subscribeTo(newSubreddit);
    }
  }

  /**
   * Whether the subreddit picker is visible, albeit partially.
   */
  private boolean isSubredditPickerVisible() {
    return !toolbarSheet.isCollapsed() && toolbarSheet.getChildAt(0) instanceof SubredditPickerSheetView;
  }

  private SubredditPickerSheetView findSubredditPickerSheet() {
    return ((SubredditPickerSheetView) toolbarSheet.getChildAt(0));
  }

// ======== USER PROFILE SHEET ======== //

  void showUserProfileSheet() {
    UserProfileSheetView pickerSheet = UserProfileSheetView.showIn(toolbarSheet);
    pickerSheet.post(() -> toolbarSheet.expand());
  }

  private boolean isUserProfileSheetVisible() {
    return !toolbarSheet.isCollapsed() && toolbarSheet.getChildAt(0) instanceof UserProfileSheetView;
  }

  @Override
  public void onBackPressed() {
    if (submissionPageLayout.isExpandedOrExpanding()) {
      submissionList.collapse();

    } else if (!toolbarSheet.isCollapsed()) {
      toolbarSheet.collapse();

    } else {
      super.onBackPressed();
    }
  }

  private void handleOnUserLogIn() {
    // Reload submissions if we're on the frontpage because the frontpage
    // submissions will change if the subscriptions change.
    subredditChangesStream
        .take(1)
        .filter(subreddit -> subscriptionManager.isFrontpage(subreddit))
        .observeOn(Schedulers.io())
        .flatMapCompletable(subreddit -> submissionRepository.clearCachedSubmissionLists(subreddit))
        .subscribe(() -> forceRefreshRequestStream.accept(Notification.INSTANCE));

    if (!submissionPageLayout.isExpanded()) {
      showUserProfileSheet();
    }
  }
}
