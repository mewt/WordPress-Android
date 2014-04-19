package org.wordpress.android.ui.reader;

import android.app.Activity;
import android.content.Context;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.app.SherlockFragment;
import com.actionbarsherlock.app.SherlockFragmentActivity;

import org.wordpress.android.Constants;
import org.wordpress.android.R;
import org.wordpress.android.datasets.ReaderPostTable;
import org.wordpress.android.datasets.ReaderTagTable;
import org.wordpress.android.models.ReaderBlogInfo;
import org.wordpress.android.models.ReaderPost;
import org.wordpress.android.models.ReaderTag;
import org.wordpress.android.ui.PullToRefreshHelper;
import org.wordpress.android.ui.PullToRefreshHelper.RefreshListener;
import org.wordpress.android.ui.WPActionBarActivity;
import org.wordpress.android.ui.prefs.UserPrefs;
import org.wordpress.android.ui.reader.actions.ReaderActions;
import org.wordpress.android.ui.reader.actions.ReaderPostActions;
import org.wordpress.android.ui.reader.adapters.ReaderActionBarTagAdapter;
import org.wordpress.android.ui.reader.adapters.ReaderPostAdapter;
import org.wordpress.android.ui.reader.adapters.ReaderPostAdapter.ReaderPostListType;
import org.wordpress.android.util.AniUtils;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.DisplayUtils;
import org.wordpress.android.util.NetworkUtils;
import org.wordpress.android.util.StringUtils;
import org.wordpress.android.util.stats.AnalyticsTracker;
import org.wordpress.android.widgets.WPNetworkImageView;

import java.util.HashMap;
import java.util.Map;

import uk.co.senab.actionbarpulltorefresh.extras.actionbarsherlock.PullToRefreshLayout;

/**
 * Fragment hosted by ReaderActivity to show posts with a specific tag
 * or by ReaderBlogDetailActivity to show posts in a specific blog
 */
public class ReaderPostListFragment extends SherlockFragment
                                    implements AbsListView.OnScrollListener,
                                               View.OnTouchListener,
                                               ActionBar.OnNavigationListener {

    static interface OnPostSelectedListener {
        public void onPostSelected(long blogId, long postId);
    }

    private ReaderPostAdapter mPostAdapter;
    private OnPostSelectedListener mPostSelectedListener;
    private ReaderFullScreenUtils.FullScreenListener mFullScreenListener;

    private PullToRefreshHelper mPullToRefreshHelper;
    private ListView mListView;
    private TextView mNewPostsBar;
    private View mEmptyView;
    private ProgressBar mProgress;

    private String mCurrentTag;
    private long mCurrentBlogId;

    private boolean mIsUpdating = false;
    private boolean mIsFlinging = false;

    private Parcelable mListState = null;

    protected static enum RefreshType { AUTOMATIC, MANUAL }

    private WPNetworkImageView mImageMshot;
    private float mLastMotionY;

    /*
     * show posts with a specific tag
     */
    static ReaderPostListFragment newInstance(final String tagName) {
        AppLog.d(T.READER, "reader post list > newInstance (tag)");

        Bundle args = new Bundle();
        args.putString(ReaderActivity.ARG_TAG_NAME, tagName);

        ReaderPostListFragment fragment = new ReaderPostListFragment();
        fragment.setArguments(args);

        return fragment;
    }

    /*
     * show posts in a specific blog
     */
    static ReaderPostListFragment newInstance(long blogId) {
        AppLog.d(T.READER, "reader post list > newInstance (blog)");

        Bundle args = new Bundle();
        args.putLong(ReaderActivity.ARG_BLOG_ID, blogId);

        ReaderPostListFragment fragment = new ReaderPostListFragment();
        fragment.setArguments(args);

        return fragment;
    }

    @Override
    public void setArguments(Bundle args) {
        super.setArguments(args);

        // note that setCurrentTag() should NOT be called here since it's automatically
        // called from the actionbar navigation handler
        if (args != null) {
            mCurrentTag = args.getString(ReaderActivity.ARG_TAG_NAME);
            mCurrentBlogId = args.getLong(ReaderActivity.ARG_BLOG_ID);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            AppLog.d(T.READER, "reader post list > restoring instance state");
            if (savedInstanceState.containsKey(ReaderActivity.ARG_TAG_NAME)) {
                mCurrentTag = savedInstanceState.getString(ReaderActivity.ARG_TAG_NAME);
            }
            if (savedInstanceState.containsKey(ReaderActivity.ARG_BLOG_ID)) {
                mCurrentBlogId = savedInstanceState.getLong(ReaderActivity.ARG_BLOG_ID);
            }
            if (savedInstanceState.containsKey(ReaderActivity.KEY_LIST_STATE)) {
                mListState = savedInstanceState.getParcelable(ReaderActivity.KEY_LIST_STATE);
            }
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        AppLog.d(T.READER, "reader post list > saving instance state");

        switch (getPostListType()) {
            case TAG:
                outState.putString(ReaderActivity.ARG_TAG_NAME, mCurrentTag);
                break;
            case BLOG:
                outState.putLong(ReaderActivity.ARG_BLOG_ID, mCurrentBlogId);
                break;
        }

        // retain list state so we can return to this position
        // http://stackoverflow.com/a/5694441/1673548
        if (mListView != null && mListView.getFirstVisiblePosition() > 0) {
            outState.putParcelable(ReaderActivity.KEY_LIST_STATE, mListView.onSaveInstanceState());
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final Context context = container.getContext();
        final View view = inflater.inflate(R.layout.reader_fragment_post_list, container, false);

        mListView = (ListView) view.findViewById(android.R.id.list);
        mListView.setOnTouchListener(this);
        mImageMshot = (WPNetworkImageView) view.findViewById(R.id.image_mshot);

        switch (getPostListType()) {
            case BLOG:
                // show mshot and blog info header for posts in a specific blog
                if (isFullScreenSupported()) {
                    //ReaderFullScreenUtils.addOverlayMargin(context, mImageMshot);
                }
                mImageMshot.setVisibility(View.VISIBLE);
                mImageMshot.setScaleType(ImageView.ScaleType.MATRIX);
                ReaderBlogInfoHeader header = new ReaderBlogInfoHeader(context);
                mListView.addHeaderView(header);
                ReaderBlogInfoHeader.OnBlogInfoListener infoListener = new ReaderBlogInfoHeader.OnBlogInfoListener() {
                    @Override
                    public void onBlogInfoShown(ReaderBlogInfo blogInfo) {
                        // set the mshots url if it hasn't already been set
                        if (hasActivity() && TextUtils.isEmpty(mImageMshot.getUrl())) {
                            int width = DisplayUtils.getDisplayPixelWidth(getActivity());
                            mImageMshot.setImageUrl(blogInfo.getMshotsUrl(width), WPNetworkImageView.ImageType.PHOTO);
                        }
                    }
                };
                header.setBlogId(mCurrentBlogId, infoListener);
                break;

            case TAG:
                mImageMshot.setVisibility(View.GONE);
                if (isFullScreenSupported()) {
                    ReaderFullScreenUtils.addListViewHeader(context, mListView);
                }
                break;
        }

        // bar that appears at top when new posts are downloaded
        mNewPostsBar = (TextView) view.findViewById(R.id.text_new_posts);
        mNewPostsBar.setVisibility(View.GONE);
        mNewPostsBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                reloadPosts(true);
                hideNewPostsBar();
            }
        });

        // textView that appears when current tag has no posts
        mEmptyView = view.findViewById(R.id.empty_view);

        // set the listView's scroll listeners so we can detect up/down scrolling
        mListView.setOnScrollListener(this);

        // tapping a post opens the detail view
        mListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int position, long id) {
                if (mPostSelectedListener != null) {
                    // take header into account
                    position -= mListView.getHeaderViewsCount();
                    ReaderPost post = (ReaderPost) getPostAdapter().getItem(position);
                    if (post != null)
                        mPostSelectedListener.onPostSelected(post.blogId, post.postId);
                }
            }
        });

        // progress bar that appears when loading more posts
        mProgress = (ProgressBar) view.findViewById(R.id.progress_footer);
        mProgress.setVisibility(View.GONE);

        // pull to refresh setup - only used when viewing posts for a specific tag
        if (getPostListType() == ReaderPostListType.TAG) {
            mPullToRefreshHelper = new PullToRefreshHelper(getActivity(),
                    (PullToRefreshLayout) view.findViewById(R.id.ptr_layout),
                    new RefreshListener() {
                        @Override
                        public void onRefreshStarted(View view) {
                            if (getActivity() == null || !NetworkUtils.checkConnection(getActivity())) {
                                mPullToRefreshHelper.setRefreshing(false);
                                return;
                            }
                            updatePostsWithCurrentTag(ReaderActions.RequestDataAction.LOAD_NEWER, RefreshType.MANUAL);
                        }
                    }
            );
        }

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        setHasOptionsMenu(true);
        checkActionBar();

        // assign the list adapter, then tell it to get the appropriate posts
        mListView.setAdapter(getPostAdapter());

        switch (getPostListType()) {
            case TAG:
                getPostAdapter().setCurrentTag(mCurrentTag);
                break;
            case BLOG:
                getPostAdapter().setCurrentBlog(mCurrentBlogId);
                updatePostsInCurrentBlog();
                break;
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        if (activity instanceof OnPostSelectedListener) {
            mPostSelectedListener = (OnPostSelectedListener) activity;
        }

        if (activity instanceof ReaderFullScreenUtils.FullScreenListener) {
            mFullScreenListener = (ReaderFullScreenUtils.FullScreenListener) activity;
        }
    }

    @Override
    public void onCreateOptionsMenu(com.actionbarsherlock.view.Menu menu, com.actionbarsherlock.view.MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        menu.clear();
        switch (getPostListType()) {
            case TAG:
                inflater.inflate(R.menu.reader_native, menu);
                checkActionBar();
                break;
            case BLOG:
                inflater.inflate(R.menu.basic_menu, menu);
                break;
        }

    }

    @Override
    public boolean onOptionsItemSelected(com.actionbarsherlock.view.MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_tags :
                ReaderActivityLauncher.showReaderTagsForResult(getActivity(), null);
                return true;
            default :
                return super.onOptionsItemSelected(item);
        }
    }

    /*
     * show/hide progress bar which appears at the bottom of the activity when loading more posts
     */
    private void showLoadingProgress() {
        if (hasActivity() && mProgress != null) {
            mProgress.setVisibility(View.VISIBLE);
        }
    }

    private void hideLoadingProgress() {
        if (hasActivity() && mProgress != null) {
            mProgress.setVisibility(View.GONE);
        }
    }

    /*
     * ensures that the ActionBar is correctly set to list navigation mode using the tag adapter
     */
    private void checkActionBar() {
        // skip out if we're in list navigation mode, since that means the actionBar is
        // already correctly configured
        final ActionBar actionBar = getActionBar();
        if (actionBar == null || actionBar.getNavigationMode() == ActionBar.NAVIGATION_MODE_LIST)
            return;

        switch (getPostListType()) {
            case TAG:
                actionBar.setDisplayShowTitleEnabled(false);
                actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
                actionBar.setListNavigationCallbacks(getActionBarAdapter(), this);
                selectTagInActionBar(getCurrentTag());
                break;

            default :
                actionBar.setDisplayShowTitleEnabled(true);
                actionBar.setDisplayHomeAsUpEnabled(true);
                actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
                break;
        }

    }


    private void startBoxAndPagesAnimation() {
        if (!hasActivity())
            return;

        Animation animPage1 = AnimationUtils.loadAnimation(getActivity(),
                R.anim.box_with_pages_slide_up_page1);
        ImageView page1 = (ImageView) getView().findViewById(R.id.empty_tags_box_page1);
        page1.startAnimation(animPage1);

        Animation animPage2 = AnimationUtils.loadAnimation(getActivity(),
                R.anim.box_with_pages_slide_up_page2);
        ImageView page2 = (ImageView) getView().findViewById(R.id.empty_tags_box_page2);
        page2.startAnimation(animPage2);

        Animation animPage3 = AnimationUtils.loadAnimation(getActivity(),
                R.anim.box_with_pages_slide_up_page3);
        ImageView page3 = (ImageView) getView().findViewById(R.id.empty_tags_box_page3);
        page3.startAnimation(animPage3);
    }

    private void setEmptyTitleAndDescriptionForCurrentTag() {
        if (!hasActivity() || getActionBarAdapter() == null)
            return;

        int title;
        int description = -1;
        if (isUpdating()) {
            title = R.string.reader_empty_posts_in_tag_updating;
        } else {
            int tagIndex = getActionBarAdapter().getIndexOfTagName(mCurrentTag);

            final String tagId;
            if (tagIndex > -1) {
                ReaderTag tag = (ReaderTag) getActionBarAdapter().getItem(tagIndex);
                tagId = tag.getStringIdFromEndpoint();
            } else {
                tagId = "";
            }
            if (tagId.equals(ReaderTag.TAG_ID_FOLLOWING)) {
                title = R.string.reader_empty_followed_blogs_title;
                description = R.string.reader_empty_followed_blogs_description;
            } else {
                if (tagId.equals(ReaderTag.TAG_ID_LIKED)) {
                    title = R.string.reader_empty_posts_liked;
                } else {
                    title = R.string.reader_empty_posts_in_tag;
                }
            }
        }

        TextView titleView = (TextView) getView().findViewById(R.id.title_empty);
        TextView descriptionView = (TextView) getView().findViewById(R.id.description_empty);
        titleView.setText(getString(title));
        if (description == -1) {
            descriptionView.setVisibility(View.INVISIBLE);
        } else {
            descriptionView.setText(getString(description));
            descriptionView.setVisibility(View.VISIBLE);
        }
    }

    /*
     * called by post adapter when data has been loaded
     */
    private final ReaderActions.DataLoadedListener mDataLoadedListener = new ReaderActions.DataLoadedListener() {
        @Override
        public void onDataLoaded(boolean isEmpty) {
            if (!hasActivity())
                return;
            // empty text/animation is only show when displaying posts with a specific tag
            if (isEmpty && getPostListType() == ReaderPostListType.TAG) {
                startBoxAndPagesAnimation();
                setEmptyTitleAndDescriptionForCurrentTag();
                mEmptyView.setVisibility(View.VISIBLE);
            } else {
                mEmptyView.setVisibility(View.GONE);
                // restore listView state - this returns to the previously scrolled-to item
                if (mListState != null && mListView != null) {
                    mListView.onRestoreInstanceState(mListState);
                    mListState = null;
                }
            }
        }
    };

    /*
     * called by post adapter to load older posts when user scrolls to the last post
     */
    private final ReaderActions.DataRequestedListener mDataRequestedListener = new ReaderActions.DataRequestedListener() {
        @Override
        public void onRequestData(ReaderActions.RequestDataAction action) {
            // skip if update is already in progress
            if (isUpdating())
                return;
            if (getPostListType() == ReaderPostListType.TAG) {
                // skip if we already have the max # of posts
                if (ReaderPostTable.getNumPostsWithTag(mCurrentTag) >= Constants.READER_MAX_POSTS_TO_DISPLAY)
                    return;
                // request older posts
                updatePostsWithCurrentTag(ReaderActions.RequestDataAction.LOAD_OLDER, RefreshType.MANUAL);
                AnalyticsTracker.track(AnalyticsTracker.Stat.READER_INFINITE_SCROLL);
            }
        }
    };

    /*
     * called by post adapter when user requests to reblog a post
     */
    private final ReaderActions.RequestReblogListener mReblogListener = new ReaderActions.RequestReblogListener() {
        @Override
        public void onRequestReblog(ReaderPost post) {
            if (hasActivity())
                ReaderActivityLauncher.showReaderReblogForResult(getActivity(), post);
        }
    };

    private ReaderPostAdapter getPostAdapter() {
        if (mPostAdapter == null)
            mPostAdapter = new ReaderPostAdapter(getActivity(),
                                                 getPostListType(),
                                                 mReblogListener,
                                                 mDataLoadedListener,
                                                 mDataRequestedListener);
        return mPostAdapter;
    }

    private boolean hasPostAdapter () {
        return (mPostAdapter != null);
    }

    protected boolean isEmpty() {
        return (mPostAdapter == null || mPostAdapter.isEmpty());
    }

    private boolean isCurrentTag(final String tagName) {
        if (!hasCurrentTag() || TextUtils.isEmpty(tagName))
            return false;
        return (mCurrentTag.equalsIgnoreCase(tagName));
    }

    private String getCurrentTag() {
        return StringUtils.notNullStr(mCurrentTag);
    }

    private boolean hasCurrentTag() {
        return !TextUtils.isEmpty(mCurrentTag);
    }

    private void setCurrentTag(final String tagName) {
        if (TextUtils.isEmpty(tagName))
            return;

        // skip if this is already the current tag and the post adapter is already showing it - this
        // will happen when the list fragment is restored and the current tag is re-selected in the
        // actionBar dropdown
        if (isCurrentTag(tagName)
                && hasPostAdapter()
                && tagName.equals(getPostAdapter().getCurrentTag()))
            return;

        mCurrentTag = tagName;
        UserPrefs.setReaderTag(tagName);

        getPostAdapter().setCurrentTag(tagName);
        hideNewPostsBar();

        // update posts in this tag if it's time to do so
        if (ReaderTagTable.shouldAutoUpdateTag(tagName))
            updatePostsWithTag(tagName, ReaderActions.RequestDataAction.LOAD_NEWER, RefreshType.AUTOMATIC);
    }

    /*
     * refresh adapter so latest posts appear
     */
    private void refreshPosts() {
        getPostAdapter().refresh();
    }

    /*
     * tell the adapter to reload a single post - called when user returns from detail, where the
     * post may have been changed (either by the user, or because it updated)
     */
    void reloadPost(ReaderPost post) {
        if (post == null)
            return;
        getPostAdapter().reloadPost(post);
    }

    /*
     * reload current tag
     */
    private void reloadPosts(boolean animateRows) {
        getPostAdapter().reload(animateRows);
    }

    private boolean hasActivity() {
        return (getActivity() != null && !isRemoving());
    }

    void checkFollowStatus() {
        if (hasPostAdapter() && !isEmpty()) {
            getPostAdapter().checkFollowStatusForAllPosts();
        }
    }

    void updateFollowStatusOnPostsForBlog(long blogId, boolean followStatus) {
        if (hasPostAdapter() && !isEmpty()) {
            getPostAdapter().updateFollowStatusOnPostsForBlog(blogId, followStatus);
        }
    }

    /*
     * get latest posts for the current blog from the server
     */
    void updatePostsInCurrentBlog() {
        final ReaderActions.RequestDataAction updateAction = ReaderActions.RequestDataAction.LOAD_NEWER;
        setIsUpdating(true, updateAction);
        ReaderPostActions.requestPostsForBlog(mCurrentBlogId, new ReaderActions.ActionListener() {
            @Override
            public void onActionResult(boolean succeeded) {
                if (!hasActivity()) {
                    return;
                }

                setIsUpdating(false, updateAction);
                if (succeeded) {
                    refreshPosts();
                }
            }
        });
    }


    /*
     * get latest posts for this tag from the server
     */
    private void updatePostsWithCurrentTag(ReaderActions.RequestDataAction updateAction, RefreshType refreshType) {
        if (hasCurrentTag()) {
            updatePostsWithTag(mCurrentTag, updateAction, refreshType);
        }
    }

    private void updatePostsWithTag(final String tagName, final ReaderActions.RequestDataAction updateAction,
                                    RefreshType refreshType) {
        if (TextUtils.isEmpty(tagName)) {
            return;
        }

        if (!NetworkUtils.isNetworkAvailable(getActivity())) {
            AppLog.i(T.READER, "reader post list > network unavailable, canceled tag update");
            return;
        }

        setIsUpdating(true, updateAction);
        setEmptyTitleAndDescriptionForCurrentTag();

        // if this is "Posts I Like" or "Blogs I Follow" and it's a manual refresh (user tapped refresh icon),
        // refresh the posts so posts that were unliked/unfollowed no longer appear
        if (refreshType == RefreshType.MANUAL && isCurrentTag(tagName)) {
            if (tagName.equals(ReaderTag.TAG_NAME_LIKED) || tagName.equals(ReaderTag.TAG_NAME_FOLLOWING))
                refreshPosts();
        }

        ReaderPostActions.updatePostsWithTag(tagName, updateAction, new ReaderActions.UpdateResultAndCountListener() {
            @Override
            public void onUpdateResult(ReaderActions.UpdateResult result, int numNewPosts) {
                if (!hasActivity()) {
                    AppLog.w(T.READER, "reader post list > volley response when fragment has no activity");
                    return;
                }

                setIsUpdating(false, updateAction);

                if (result == ReaderActions.UpdateResult.CHANGED && numNewPosts > 0 && isCurrentTag(tagName)) {
                    // if we loaded new posts and posts are already displayed, show the "new posts"
                    // bar rather than immediately refreshing the list
                    if (!isEmpty() && updateAction == ReaderActions.RequestDataAction.LOAD_NEWER) {
                        showNewPostsBar(numNewPosts);
                    } else {
                        refreshPosts();
                    }
                } else {
                    // update empty view title and description if the the post list is empty
                    setEmptyTitleAndDescriptionForCurrentTag();
                }
            }
        });
    }

    boolean isUpdating() {
        return mIsUpdating;
    }

    private boolean hasPullToRefresh() {
        return (mPullToRefreshHelper != null);
    }

    public void setIsUpdating(boolean isUpdating, ReaderActions.RequestDataAction updateAction) {
        if (!hasActivity() || mIsUpdating == isUpdating) {
            return;
        }
        switch (updateAction) {
            case LOAD_OLDER:
                // if these are older posts, show/hide message bar at bottom
                if (isUpdating) {
                    showLoadingProgress();
                } else {
                    hideLoadingProgress();
                }
                break;
            default:
                if (hasPullToRefresh()) {
                    mPullToRefreshHelper.setRefreshing(isUpdating);
                }
                break;
        }
        mIsUpdating = isUpdating;
    }

    /*
     * bar that appears at the top when new posts have been retrieved
     */
    private void showNewPostsBar(int numNewPosts) {
        if (mNewPostsBar==null || mNewPostsBar.getVisibility()==View.VISIBLE)
            return;
        if (numNewPosts==1) {
            mNewPostsBar.setText(R.string.reader_label_new_posts_one);
        } else {
            mNewPostsBar.setText(getString(R.string.reader_label_new_posts_multi, numNewPosts));
        }
        AniUtils.startAnimation(mNewPostsBar, R.anim.reader_top_bar_in);
        mNewPostsBar.setVisibility(View.VISIBLE);
        if (hasPullToRefresh()) {
            mPullToRefreshHelper.hideTipTemporarily(true);
        }
    }

    private void hideNewPostsBar() {
        if (mNewPostsBar==null || mNewPostsBar.getVisibility()!=View.VISIBLE)
            return;
        Animation.AnimationListener listener = new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) { }
            @Override
            public void onAnimationEnd(Animation animation) {
                mNewPostsBar.setVisibility(View.GONE);
            }
            @Override
            public void onAnimationRepeat(Animation animation) { }
        };
        AniUtils.startAnimation(mNewPostsBar, R.anim.reader_top_bar_out, listener);
        if (hasPullToRefresh()) {
            mPullToRefreshHelper.showTip(true);
        }
    }

    /*
     * make sure current tag still exists, reset to default if it doesn't
     */
    private void checkCurrentTag() {
        if (hasCurrentTag() && !ReaderTagTable.tagExists(getCurrentTag()))
            mCurrentTag = ReaderTag.TAG_NAME_DEFAULT;
    }

    /*
     * refresh the list of tags shown in the ActionBar
     */
    void refreshTags() {
        if (!hasActivity())
            return;
        checkCurrentTag();
        getActionBarAdapter().refreshTags();
    }

    /*
     * called from ReaderActivity after user adds/removes tags
     */
    void doTagsChanged(final String newCurrentTag) {
        checkCurrentTag();
        getActionBarAdapter().reloadTags();
        if (!TextUtils.isEmpty(newCurrentTag))
            setCurrentTag(newCurrentTag);
    }

    /*
     * are we showing all posts with a specific tag, or all posts in a specific blog?
     */
    ReaderPostListType getPostListType() {
        if (hasCurrentTag()) {
            return ReaderPostListType.TAG;
        } else {
            return ReaderPostListType.BLOG;
        }
    }

    @Override
    public void onScrollStateChanged(AbsListView absListView, int scrollState) {
        boolean isFlingingNow = (scrollState == SCROLL_STATE_FLING);
        if (isFlingingNow != mIsFlinging) {
            mIsFlinging = isFlingingNow;
            if (hasPostAdapter())
                getPostAdapter().setIsFlinging(mIsFlinging);
        }
    }

    @Override
    public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
        // nop
    }

    /*
     * ActionBar tag dropdown adapter
     */
    private ReaderActionBarTagAdapter mActionBarAdapter;
    private ReaderActionBarTagAdapter getActionBarAdapter() {
        if (mActionBarAdapter == null) {
            ReaderActions.DataLoadedListener dataListener = new ReaderActions.DataLoadedListener() {
                @Override
                public void onDataLoaded(boolean isEmpty) {
                    if (!hasActivity())
                        return;
                    AppLog.d(T.READER, "reader post list > ActionBar adapter loaded");
                    selectTagInActionBar(getCurrentTag());
                }
            };

            boolean isStaticMenuDrawer;
            if (getActivity() instanceof WPActionBarActivity) {
                isStaticMenuDrawer = ((WPActionBarActivity)getActivity()).isStaticMenuDrawer();
            } else {
                isStaticMenuDrawer = false;
            }
            mActionBarAdapter = new ReaderActionBarTagAdapter(getActivity(), isStaticMenuDrawer, dataListener);
        }

        return mActionBarAdapter;
    }

    private ActionBar getActionBar() {
        if (getActivity() instanceof SherlockFragmentActivity) {
            return ((SherlockFragmentActivity)getActivity()).getSupportActionBar();
        } else {
            AppLog.w(T.READER, "reader post list > null ActionBar");
            return null;
        }
    }

    /*
     * make sure the passed tag is the one selected in the actionbar
     */
    private void selectTagInActionBar(final String tagName) {
        if (TextUtils.isEmpty(tagName))
            return;

        ActionBar actionBar = getActionBar();
        if (actionBar == null)
            return;

        int position = getActionBarAdapter().getIndexOfTagName(tagName);
        if (position == -1 || position == actionBar.getSelectedNavigationIndex())
            return;

        if (actionBar.getNavigationMode() != ActionBar.NAVIGATION_MODE_LIST) {
            AppLog.w(T.READER, "reader post list > unexpected ActionBar navigation mode");
            return;
        }

        actionBar.setSelectedNavigationItem(position);
    }

    /*
     * called when user selects a tag from the ActionBar dropdown
     */
    @Override
    public boolean onNavigationItemSelected(int itemPosition, long itemId) {
        final ReaderTag tag = (ReaderTag) getActionBarAdapter().getItem(itemPosition);
        if (tag == null) {
            return false;
        }

        if (!isCurrentTag(tag.getTagName())) {
            Map<String, String> properties = new HashMap<String, String>();
            properties.put("tag", tag.getTagName());
            AnalyticsTracker.track(AnalyticsTracker.Stat.READER_LOADED_TAG, properties);
            if (tag.getTagName().equals(ReaderTag.TAG_NAME_FRESHLY_PRESSED)) {
                AnalyticsTracker.track(AnalyticsTracker.Stat.READER_LOADED_FRESHLY_PRESSED);
            }
        }

        setCurrentTag(tag.getTagName());
        AppLog.d(T.READER, "reader post list > tag chosen from actionbar: " + tag.getTagName());

        return true;
    }

    private boolean isFullScreenSupported() {
        return (mFullScreenListener != null && mFullScreenListener.isFullScreenSupported());
    }

    private void scaleMshotImage(float yPos, boolean enlarge) {
        float scaleFactor = yPos * 0.000005f;

        final float scale;
        if (enlarge) {
            scale = 1.0f + scaleFactor;
            mImageMshot.setVisibility(View.VISIBLE);
        } else {
            scale = 1.0f - scaleFactor;
            if (scale <= 0) {
                mImageMshot.setVisibility(View.GONE);
                return;
            }
        }

        int centerX = mImageMshot.getWidth() / 2;
        Matrix matrix = mImageMshot.getImageMatrix();
        matrix.postScale(scale, scale, centerX, 0);
        mImageMshot.setImageMatrix(matrix);
        mImageMshot.invalidate();
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        int action = event.getAction() & MotionEvent.ACTION_MASK;
        final float y = event.getY();
        final int yDiff = (int) (y - mLastMotionY);
        mLastMotionY = y;

        if (getPostListType() == ReaderPostListType.BLOG) {
            switch (action) {
                case MotionEvent.ACTION_MOVE:
                    ListView listView = (ListView) view;
                    if (yDiff < 0) {
                        // user is scrolling down
                        scaleMshotImage(y, false);
                    } else if (yDiff > 0 && ReaderFullScreenUtils.canScrollUp(listView)) {
                        // user is scrolling up
                        scaleMshotImage(y, true);
                    }
                    break;

                default:
                    break;
            }
        }

        return false;
    }
}
