package androidx.paging

import androidx.annotation.GuardedBy
import io.realm.RealmModel

/**
 * @author S.A.Bobrischev
 * Developed by Magora Team (magora-systems.com). 02.06.18.
 */
abstract class RealmPageKeyedDataSource<Key, Value : RealmModel> : RealmContiguousDataSource<Key, Value>() {
    @GuardedBy("mKeyLock")
    private var mNextKey: Key? = null

    @GuardedBy("mKeyLock")
    private var mPreviousKey: Key? = null

    private var previousKey: Key?
        get() = synchronized(mKeyLock) {
            return mPreviousKey
        }
        set(previousKey) = synchronized(mKeyLock) {
            mPreviousKey = previousKey
        }

    private var nextKey: Key?
        get() = synchronized(mKeyLock) {
            return mNextKey
        }
        set(nextKey) = synchronized(mKeyLock) {
            mNextKey = nextKey
        }

    private fun initKeys(previousKey: Key?, nextKey: Key?) {
        synchronized(mKeyLock) {
            mPreviousKey = previousKey
            mNextKey = nextKey
        }
    }

    class LoadInitialParams(val requestedLoadSize: Int)

    class LoadParams<Key>(
            /**
             * Load items before/after this key.
             *
             * Returned data must begin directly adjacent to this position.
             */
            val key: Key,
            /**
             * Requested number of items to load.
             *
             * Returned page can be of this size, but it may be altered if that is easier, e.g. a
             * network data source where the backend defines page size.
             */
            val requestedLoadSize: Int,
            val currentItemsCount: Int)

    abstract class LoadInitialCallback<Key, Value> {
        /**
         * Called to pass initial load state from a DataSource.
         *
         *
         * Call this method from your DataSource's `loadInitial` function to return data,
         * and inform how many placeholders should be shown before and after. If counting is cheap
         * to compute (for example, if a network load returns the information regardless), it's
         * recommended to pass data back through this method.
         *
         *
         * It is always valid to pass a different amount of data than what is requested. Pass an
         * empty list if there is no more data to load.
         *
         * @param loadedCount List of items loaded from the DataSource. If this is empty, the DataSource
         * is treated as empty, and no further loads will occur.
         * @param position    Position of the item at the front of the list. If there are `N`
         * items before the items in data that can be loaded from this DataSource,
         * pass `N`.
         * @param totalCount  Total number of items that may be returned from this DataSource.
         * Includes the number in the initial `data` parameter
         * as well as any items that can be loaded in front or behind of
         * `data`.
         */
        abstract fun onResult(loadedCount: Int, position: Int, totalCount: Int, previousPageKey: Key?, nextPageKey: Key?)

        abstract fun onResult(loadedCount: Int, previousPageKey: Key?, nextPageKey: Key?)
    }

    /**
     * Callback for PageKeyedDataSource [.loadBefore] and
     * [.loadAfter] to return data.
     *
     *
     * A callback can be called only once, and will throw if called again.
     *
     *
     * It is always valid for a DataSource loading method that takes a callback to stash the
     * callback and call it later. This enables DataSources to be fully asynchronous, and to handle
     * temporary, recoverable error states (such as a network error that can be retried).
     *
     * @param <Key>   Type of data used to query pages.
     * @param <Value> Type of items being loaded.
    </Value></Key> */
    abstract class LoadCallback<Key, Value> {

        /**
         * Called to pass loaded data from a DataSource.
         *
         *
         * Call this method from your PageKeyedDataSource's
         * [.loadBefore] and
         * [.loadAfter] methods to return data.
         *
         *
         * It is always valid to pass a different amount of data than what is requested. Pass an
         * empty list if there is no more data to load.
         *
         *
         * Pass the key for the subsequent page to load to adjacentPageKey. For example, if you've
         * loaded a page in [.loadBefore], pass the key for the
         * previous page, or `null` if the loaded page is the first. If in
         * [.loadAfter], pass the key for the next page, or
         * `null` if the loaded page is the last.
         *
         * @param loadedCount     List of items loaded from the PageKeyedDataSource.
         * @param adjacentPageKey Key for subsequent page load (previous page in [.loadBefore]
         * / next page in [.loadAfter]), or `null` if there are
         * no more pages to load in the current load direction.
         */
        abstract fun onResult(loadedCount: Int, adjacentPageKey: Key?)
    }

    internal class LoadInitialCallbackImpl<Key, Value : RealmModel>(
            private val mDataSource: RealmPageKeyedDataSource<Key, Value>,
            receiver: RealmPageResult.Receiver) : RealmPageKeyedDataSource.LoadInitialCallback<Key, Value>() {
        private val mCallbackHelper = RealmDataSource.LoadCallbackHelper(mDataSource, RealmPageResult.INIT, receiver)

        override fun onResult(loadedCount: Int, position: Int, totalCount: Int, previousPageKey: Key?, nextPageKey: Key?) {
            if (!mCallbackHelper.dispatchInvalidResultIfInvalid()) {
                // setup keys before dispatching data, so guaranteed to be ready
                mDataSource.initKeys(previousPageKey, nextPageKey)
                mCallbackHelper.dispatchResultToReceiver(RealmPageResult(loadedCount))
            }
        }

        override fun onResult(loadedCount: Int, previousPageKey: Key?, nextPageKey: Key?) {
            if (!mCallbackHelper.dispatchInvalidResultIfInvalid()) {
                mDataSource.initKeys(previousPageKey, nextPageKey)
                mCallbackHelper.dispatchResultToReceiver(RealmPageResult(loadedCount))
            }
        }
    }

    internal class LoadCallbackImpl<Key, Value : RealmModel>(
            private val mDataSource: RealmPageKeyedDataSource<Key, Value>,
            @RealmPageResult.ResultType type: Int,
            receiver: RealmPageResult.Receiver) : RealmPageKeyedDataSource.LoadCallback<Key, Value>() {
        private val mCallbackHelper = RealmDataSource.LoadCallbackHelper(mDataSource, type, receiver)

        override fun onResult(loadedCount: Int, adjacentPageKey: Key?) {
            if (!mCallbackHelper.dispatchInvalidResultIfInvalid()) {
                if (mCallbackHelper.mResultType == RealmPageResult.APPEND) {
                    mDataSource.nextKey = adjacentPageKey
                } else {
                    mDataSource.previousKey = adjacentPageKey
                }
                mCallbackHelper.dispatchResultToReceiver(RealmPageResult(loadedCount))
            }
        }
    }

    override fun getKey(position: Int, item: Value?): Key? {
        // don't attempt to persist keys, since we currently don't pass them to initial load
        return null
    }

    override fun dispatchLoadInitial(key: Key?,
                                     initialLoadSize: Int,
                                     pageSize: Int,
                                     receiver: RealmPageResult.Receiver) {
        loadInitial(
                RealmPageKeyedDataSource.LoadInitialParams(initialLoadSize),
                RealmPageKeyedDataSource.LoadInitialCallbackImpl(this, receiver)
        )
    }


    override fun dispatchLoadAfter(currentEndIndex: Int,
                                   currentItemsCount: Int,
                                   currentEndItem: Value,
                                   pageSize: Int,
                                   receiver: RealmPageResult.Receiver) {
        val key = nextKey
        if (key != null) {
            loadAfter(
                    RealmPageKeyedDataSource.LoadParams(key, pageSize, currentItemsCount),
                    RealmPageKeyedDataSource.LoadCallbackImpl(this, RealmPageResult.APPEND, receiver)
            )
        }
    }

    override fun dispatchLoadBefore(currentBeginIndex: Int,
                                    currentItemsCount: Int,
                                    currentBeginItem: Value,
                                    pageSize: Int,
                                    receiver: RealmPageResult.Receiver) {
        val key = previousKey
        if (key != null) {
            loadBefore(
                    RealmPageKeyedDataSource.LoadParams(key, pageSize, currentItemsCount),
                    RealmPageKeyedDataSource.LoadCallbackImpl(this, RealmPageResult.PREPEND, receiver)
            )
        }
    }

    /**
     * Load initial data.
     *
     *
     * This method is called first to initialize a PagedList with data. If it's possible to count
     * the items that can be loaded by the DataSource, it's recommended to pass the loaded data to
     * the callback via the three-parameter
     * [RealmPageKeyedDataSource.LoadInitialCallback.onResult]. This enables PagedLists
     * presenting data from this source to display placeholders to represent unloaded items.
     *
     *
     * [RealmPageKeyedDataSource.LoadInitialParams.requestedLoadSize] is a hint, not a requirement, so it may be may be
     * altered or ignored.
     *
     * @param params   Parameters for initial load, including requested load size.
     * @param callback Callback that receives initial load data.
     */
    abstract fun loadInitial(params: RealmPageKeyedDataSource.LoadInitialParams,
                             callback: RealmPageKeyedDataSource.LoadInitialCallback<Key, Value>)

    /**
     * Prepend page with the key specified by [LoadParams.key][RealmPageKeyedDataSource.LoadParams.key].
     *
     *
     * It's valid to return a different list size than the page size if it's easier, e.g. if your
     * backend defines page sizes. It is generally safer to increase the number loaded than reduce.
     *
     *
     * Data may be passed synchronously during the load method, or deferred and called at a
     * later time. Further loads going down will be blocked until the callback is called.
     *
     *
     * If data cannot be loaded (for example, if the request is invalid, or the data would be stale
     * and inconsistent, it is valid to call [.invalidate] to invalidate the data source,
     * and prevent further loading.
     *
     * @param params   Parameters for the load, including the key for the new page, and requested load
     * size.
     * @param callback Callback that receives loaded data.
     */
    abstract fun loadBefore(params: RealmPageKeyedDataSource.LoadParams<Key>,
                            callback: RealmPageKeyedDataSource.LoadCallback<Key, Value>)

    /**
     * Append page with the key specified by [LoadParams.key][RealmPageKeyedDataSource.LoadParams.key].
     *
     *
     * It's valid to return a different list size than the page size if it's easier, e.g. if your
     * backend defines page sizes. It is generally safer to increase the number loaded than reduce.
     *
     *
     * Data may be passed synchronously during the load method, or deferred and called at a
     * later time. Further loads going down will be blocked until the callback is called.
     *
     *
     * If data cannot be loaded (for example, if the request is invalid, or the data would be stale
     * and inconsistent, it is valid to call [.invalidate] to invalidate the data source,
     * and prevent further loading.
     *
     * @param params   Parameters for the load, including the key for the new page, and requested load
     * size.
     * @param callback Callback that receives loaded data.
     */
    abstract fun loadAfter(params: RealmPageKeyedDataSource.LoadParams<Key>,
                           callback: RealmPageKeyedDataSource.LoadCallback<Key, Value>)


    companion object {
        private val mKeyLock = Any()
    }
}
