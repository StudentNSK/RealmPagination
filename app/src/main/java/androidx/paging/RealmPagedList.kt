package androidx.paging

import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.RestrictTo
import io.realm.OrderedRealmCollection
import io.realm.RealmModel
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * @author S.A.Bobrischev
 * Developed by Magora Team (magora-systems.com). 02.06.18.
 */
abstract class RealmPagedList<T : RealmModel> internal constructor(
        internal val mStorage: RealmPagedStorage<T>,
        internal val mBoundaryCallback: RealmPagedList.BoundaryCallback<T>?,
        val config: RealmPagedList.Config) : AbstractList<T>() {
    private val mainThreadHandler = Handler(Looper.getMainLooper())
    internal var mLastLoad = 0
    internal var mLastItem: T? = null
    private val mDetached = AtomicBoolean(false)

    // if set to true, mBoundaryCallback is non-null, and should
    // be dispatched when nearby load has occurred
    private var mBoundaryCallbackBeginDeferred = false
    private var mBoundaryCallbackEndDeferred = false

    // lowest and highest index accessed by loadAround. Used to
    // decide when mBoundaryCallback should be dispatched
    private var mLowestIndexAccessed = Integer.MAX_VALUE
    private var mHighestIndexAccessed = Integer.MIN_VALUE

    abstract val dataSource: RealmDataSource<*, T>
    abstract val lastKey: Any?

    /**
     * Returns size of the list, including any not-yet-loaded null padding.
     *
     * @return Current total size of the list.
     */
    override val size: Int get() = mStorage.size

    val isDetached: Boolean get() = mDetached.get()

    class Builder<Key, Value : RealmModel>(
            private val realmData: OrderedRealmCollection<Value>,
            private val mDataSource: RealmDataSource<Key, Value>,
            private val mConfig: RealmPagedList.Config) {
        private var mBoundaryCallback: RealmPagedList.BoundaryCallback<Value>? = null
        private var mInitialKey: Key? = null

        fun setBoundaryCallback(boundaryCallback: RealmPagedList.BoundaryCallback<Value>?): RealmPagedList.Builder<Key, Value> {
            mBoundaryCallback = boundaryCallback
            return this
        }

        fun setInitialKey(initialKey: Key?): RealmPagedList.Builder<Key, Value> {
            mInitialKey = initialKey
            return this
        }

        fun build(): RealmPagedList<Value> = create(realmData, mDataSource, mBoundaryCallback, mConfig, mInitialKey)
    }

    /**
     * Get the item in the list of loaded items at the provided index.
     *
     * @param index Index in the loaded item list. Must be >= 0, and &lt; [.size]
     * @return The item at the passed index, or null if a null placeholder is at the specified
     * position.
     * @see .size
     */
    override fun get(index: Int): T? {
        val item = mStorage[index]
        if (item != null) {
            mLastItem = item
        }
        return item
    }

    /**
     * Load adjacent items to passed index.
     *
     * @param index Index at which to load.
     */
    fun loadAround(index: Int) {
        mLastLoad = index
        loadAroundInternal(index)

        mLowestIndexAccessed = min(mLowestIndexAccessed, index)
        mHighestIndexAccessed = max(mHighestIndexAccessed, index)

        /*
         * mLowestIndexAccessed / mHighestIndexAccessed have been updated, so check if we need to
         * dispatch boundary callbacks. Boundary callbacks are deferred until last items are loaded,
         * and accesses happen near the boundaries.
         *
         * Note: we post here, since RecyclerView may want to add items in response, and this
         * call occurs in PagedListAdapter bind.
         */
        tryDispatchBoundaryCallbacks(true)
    }

    // Creation thread for initial synchronous load, otherwise main thread
    // Safe to access main thread only state - no other thread has reference during construction
    internal fun deferBoundaryCallbacks(deferEmpty: Boolean, deferBegin: Boolean, deferEnd: Boolean) {
        /*
         * If lowest/highest haven't been initialized, set them to storage size,
         * since placeholders must already be computed by this point.
         *
         * This is just a minor optimization so that BoundaryCallback callbacks are sent immediately
         * if the initial load size is smaller than the prefetch window (see
         * TiledPagedListTest#boundaryCallback_immediate())
         */
        if (mLowestIndexAccessed == Integer.MAX_VALUE) {
            mLowestIndexAccessed = mStorage.size
        }
        if (mHighestIndexAccessed == Integer.MIN_VALUE) {
            mHighestIndexAccessed = 0
        }

        if (deferEmpty || deferBegin || deferEnd) {
            if (deferEmpty) {
                mBoundaryCallback?.onZeroItemsLoaded()
            }

            // for other callbacks, mark deferred, and only dispatch if loadAround
            // has been called near to the position
            if (deferBegin) {
                mBoundaryCallbackBeginDeferred = true
            }
            if (deferEnd) {
                mBoundaryCallbackEndDeferred = true
            }
            tryDispatchBoundaryCallbacks(false)
        }
    }

    /**
     * Call this when mLowest/HighestIndexAccessed are changed, or
     * mBoundaryCallbackBegin/EndDeferred is set.
     */
    private fun tryDispatchBoundaryCallbacks(post: Boolean) {
        val dispatchBegin = mBoundaryCallbackBeginDeferred && mLowestIndexAccessed <= config.prefetchDistance
        val dispatchEnd = mBoundaryCallbackEndDeferred && mHighestIndexAccessed >= size - 1 - config.prefetchDistance

        if (!dispatchBegin && !dispatchEnd) {
            return
        }
        if (dispatchBegin) {
            mBoundaryCallbackBeginDeferred = false
        }
        if (dispatchEnd) {
            mBoundaryCallbackEndDeferred = false
        }
        if (post) {
            mainThreadHandler.post { dispatchBoundaryCallbacks(dispatchBegin, dispatchEnd) }
        } else {
            dispatchBoundaryCallbacks(dispatchBegin, dispatchEnd)
        }
    }

    private fun dispatchBoundaryCallbacks(begin: Boolean, end: Boolean) {
        if (begin) {
            mBoundaryCallback?.onItemAtFrontLoaded(mStorage.firstLoadedItem!!)
        }
        if (end) {
            mBoundaryCallback?.onItemAtEndLoaded(mStorage.lastLoadedItem!!)
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    internal fun offsetBoundaryAccessIndices(offset: Int) {
        mLowestIndexAccessed += offset
        mHighestIndexAccessed += offset
    }

    /**
     * Detach the PagedList from its DataSource, and attempt to load no more data.
     *
     *
     * This is called automatically when a DataSource load returns `null`, which is a
     * signal to stop loading. The PagedList will continue to present existing data, but will not
     * initiate new loads.
     */
    fun detach() {
        mDetached.set(true)
    }

    internal abstract fun loadAroundInternal(index: Int)

    /**
     * Configures how a PagedList loads content from its DataSource.
     *
     *
     * Use a Config [RealmPagedList.Config.Builder] to construct and define custom loading behavior, such as
     * [RealmPagedList.Config.Builder.setPageSize], which defines number of items loaded at a time}.
     */
    class Config private constructor(
            /**
             * Size of each page loaded by the PagedList.
             */
            val pageSize: Int,
            /**
             * Prefetch distance which defines how far ahead to load.
             *
             *
             * If this value is set to 50, the paged list will attempt to load 50 items in advance of
             * data that's already been accessed.
             *
             * @see PagedList.loadAround
             */
            val prefetchDistance: Int,
            /**
             * Size hint for initial load of PagedList, often larger than a regular page.
             */
            val initialLoadSizeHint: Int) {

        /**
         * Builder class for [RealmPagedList.Config].
         *
         *
         * You must at minimum specify page size with [.setPageSize].
         */
        class Builder {
            private var mPageSize = -1
            private var mPrefetchDistance = -1
            private var mInitialLoadSizeHint = -1

            /**
             * Defines the number of items loaded at once from the DataSource.
             *
             *
             * Should be several times the number of visible items onscreen.
             *
             *
             * Configuring your page size depends on how your data is being loaded and used. Smaller
             * page sizes improve memory usage, latency, and avoid GC churn. Larger pages generally
             * improve loading throughput, to a point
             * (avoid loading more than 2MB from SQLite at once, since it incurs extra cost).
             *
             *
             * If you're loading data for very large, social-media style cards that take up most of
             * a screen, and your database isn't a bottleneck, 10-20 may make sense. If you're
             * displaying dozens of items in a tiled grid, which can present items during a scroll
             * much more quickly, consider closer to 100.
             *
             * @param pageSize Number of items loaded at once from the DataSource.
             * @return this
             */
            fun setPageSize(pageSize: Int): RealmPagedList.Config.Builder {
                this.mPageSize = pageSize
                return this
            }

            /**
             * Defines how far from the edge of loaded content an access must be to trigger further
             * loading.
             *
             *
             * Should be several times the number of visible items onscreen.
             *
             *
             * If not set, defaults to page size.
             *
             *
             * A value of 0 indicates that no list items will be loaded until they are specifically
             * requested. This is generally not recommended, so that users don't observe a
             * placeholder item (with placeholders) or end of list (without) while scrolling.
             *
             * @param prefetchDistance Distance the PagedList should prefetch.
             * @return this
             */
            fun setPrefetchDistance(prefetchDistance: Int): RealmPagedList.Config.Builder {
                this.mPrefetchDistance = prefetchDistance
                return this
            }

            /**
             * Defines how many items to load when first load occurs.
             *
             *
             * This value is typically larger than page size, so on first load data there's a large
             * enough range of content loaded to cover small scrolls.
             *
             *
             * When using a [PositionalDataSource], the initial load size will be coerced to
             * an integer multiple of pageSize, to enable efficient tiling.
             *
             *
             * If not set, defaults to three times page size.
             *
             * @param initialLoadSizeHint Number of items to load while initializing the PagedList.
             * @return this
             */
            fun setInitialLoadSizeHint(initialLoadSizeHint: Int): RealmPagedList.Config.Builder {
                this.mInitialLoadSizeHint = initialLoadSizeHint
                return this
            }

            fun build(): RealmPagedList.Config {
                if (mPageSize < 1) {
                    throw IllegalArgumentException("Page size must be a positive number")
                }
                if (mPrefetchDistance < 0) {
                    mPrefetchDistance = mPageSize
                }
                if (mInitialLoadSizeHint < 0) {
                    mInitialLoadSizeHint = mPageSize * 3
                }

                return RealmPagedList.Config(mPageSize, mPrefetchDistance, mInitialLoadSizeHint)
            }
        }
    }

    /**
     * Signals when a PagedList has reached the end of available data.
     *
     *
     * When local storage is a cache of network data, it's common to set up a streaming pipeline:
     * Network data is paged into the database, database is paged into UI. Paging from the database
     * to UI can be done with a `LiveData<PagedList>`, but it's still necessary to know when
     * to trigger network loads.
     *
     *
     * BoundaryCallback does this signaling - when a DataSource runs out of data at the end of
     * the list, [.onItemAtEndLoaded] is called, and you can start an async network
     * load that will write the result directly to the database. Because the database is being
     * observed, the UI bound to the `LiveData<PagedList>` will update automatically to
     * account for the new items.
     *
     *
     * Note that a BoundaryCallback instance shared across multiple PagedLists (e.g. when passed to
     * [LivePagedListBuilder.setBoundaryCallback]), the callbacks may be issued multiple
     * times. If for example [.onItemAtEndLoaded] triggers a network load, it should
     * avoid triggering it again while the load is ongoing.
     *
     *
     * BoundaryCallback only passes the item at front or end of the list. Number of items is not
     * passed, since it may not be fully computed by the DataSource if placeholders are not
     * supplied. Keys are not known because the BoundaryCallback is independent of the
     * DataSource-specific keys, which may be different for local vs remote storage.
     *
     *
     * The database + network Repository in the
     * [PagingWithNetworkSample](https://github.com/googlesamples/android-architecture-components/blob/master/PagingWithNetworkSample/README.md)
     * shows how to implement a network BoundaryCallback using
     * [Retrofit](https://square.github.io/retrofit/), while
     * handling swipe-to-refresh, network errors, and retry.
     *
     * @param <T> Type loaded by the PagedList.
    </T> */
    @MainThread
    abstract class BoundaryCallback<T> {
        /**
         * Called when zero items are returned from an initial load of the PagedList's data source.
         */
        open fun onZeroItemsLoaded() {}

        /**
         * Called when the item at the front of the PagedList has been loaded, and access has
         * occurred within [PagedList.Config.prefetchDistance] of it.
         *
         *
         * No more data will be prepended to the PagedList before this item.
         *
         * @param itemAtFront The first item of PagedList
         */
        open fun onItemAtFrontLoaded(itemAtFront: T) {}

        /**
         * Called when the item at the end of the PagedList has been loaded, and access has
         * occurred within [PagedList.Config.prefetchDistance] of it.
         *
         *
         * No more data will be appended to the PagedList after this item.
         *
         * @param itemAtEnd The first item of PagedList
         */
        open fun onItemAtEndLoaded(itemAtEnd: T) {}
    }

    companion object {

        /**
         * Create a PagedList which loads data from the provided data source on a background thread,
         * posting updates to the main thread.
         *
         * @param dataSource       DataSource providing data to the PagedList
         * should be a background thread.
         * @param boundaryCallback Optional boundary callback to attach to the list.
         * @param config           PagedList Config, which defines how the PagedList will load data.
         * @param <K>              Key type that indicates to the DataSource what data to load.
         * @param <T>              Type of items to be held and loaded by the PagedList.
         * @return Newly created PagedList, which will page in data from the DataSource as needed.
        </T></K> */
        private fun <K, T : RealmModel> create(realmData: OrderedRealmCollection<T>,
                                               dataSource: RealmDataSource<K, T>,
                                               boundaryCallback: RealmPagedList.BoundaryCallback<T>?,
                                               config: RealmPagedList.Config,
                                               key: K?): RealmPagedList<T> {
            return RealmContiguousPagedList(
                    realmData,
                    dataSource as RealmContiguousDataSource<K, T>,
                    boundaryCallback,
                    config,
                    key,
                    RealmContiguousPagedList.LAST_LOAD_UNSPECIFIED)
        }
    }
}
