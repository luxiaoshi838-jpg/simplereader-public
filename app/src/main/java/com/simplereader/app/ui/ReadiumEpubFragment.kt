package com.simplereader.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.simplereader.app.R
import com.simplereader.app.readium.ReadiumSessionStore
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.toUri

@OptIn(ExperimentalReadiumApi::class)
class ReadiumEpubFragment : Fragment(),
    EpubNavigatorFragment.Listener,
    EpubNavigatorFragment.PaginationListener {

    interface Host {
        fun onReadiumNavigatorReady(fragment: ReadiumEpubFragment)
        fun onReadiumLocatorChanged(locator: Locator)
        fun toggleReadiumChrome()
        fun readiumPresentationCss(): String
    }

    private val bookId: Long by lazy {
        requireArguments().getLong(ARG_BOOK_ID)
    }

    lateinit var navigator: EpubNavigatorFragment
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        val session = ReadiumSessionStore[bookId]
        childFragmentManager.fragmentFactory = if (session == null) {
            EpubNavigatorFragment.createDummyFactory()
        } else {
            session.navigatorFactory.createFragmentFactory(
                initialLocator = session.initialLocator,
                listener = this,
                paginationListener = this,
                configuration = EpubNavigatorFragment.Configuration(
                    shouldApplyInsetsPadding = true
                )
            )
        }
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_readium_epub, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val session = ReadiumSessionStore[bookId]
        if (session == null) {
            requireActivity().finish()
            return
        }

        if (savedInstanceState == null) {
            childFragmentManager.commitNow {
                replace(
                    R.id.readiumNavigatorHost,
                    EpubNavigatorFragment::class.java,
                    Bundle(),
                    NAVIGATOR_TAG
                )
            }
        }

        navigator = childFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as EpubNavigatorFragment
        attachInputHandling()
        observeLocation()
        (activity as? Host)?.onReadiumNavigatorReady(this)
    }

    private fun attachInputHandling() {
        navigator.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                val view = navigator.publicationView
                val width = view.width.takeIf { it > 0 } ?: return false
                val height = view.height.takeIf { it > 0 } ?: return false
                val point = event.point
                val inCenter = point.x in (width / 4f)..(width * 3f / 4f) &&
                    point.y in (height / 4f)..(height * 3f / 4f)
                if (!inCenter) return false
                (activity as? Host)?.toggleReadiumChrome()
                return true
            }
        })
        navigator.addInputListener(
            DirectionalNavigationAdapter(
                navigator = navigator,
                animatedTransition = true
            )
        )
    }

    private fun observeLocation() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                navigator.currentLocator.collect { locator ->
                    (activity as? Host)?.onReadiumLocatorChanged(locator)
                }
            }
        }
    }

    override fun onPageLoaded() {
        applyPresentationCss()
    }

    fun applyPresentationCss() {
        val css = (activity as? Host)?.readiumPresentationCss() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            navigator.evaluateJavascript(
                """
                (function() {
                    var style = document.getElementById('simplereader-runtime-style');
                    if (!style) {
                        style = document.createElement('style');
                        style.id = 'simplereader-runtime-style';
                        document.head.appendChild(style);
                    }
                    style.textContent = ${org.json.JSONObject.quote(css)};
                })();
                """.trimIndent()
            )
        }
    }

    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }
    }

    companion object {
        private const val ARG_BOOK_ID = "bookId"
        private const val NAVIGATOR_TAG = "readiumEpubNavigator"

        fun newInstance(bookId: Long): ReadiumEpubFragment =
            ReadiumEpubFragment().apply {
                arguments = Bundle().apply { putLong(ARG_BOOK_ID, bookId) }
            }
    }
}
