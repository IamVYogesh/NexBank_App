package com.nexbank.app.feature.support

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.nexbank.app.R
import com.nexbank.app.core.config.Country
import com.nexbank.app.core.config.CountryConfigProvider
import com.nexbank.app.databinding.FragmentHelpDetailBinding
import com.nexbank.app.databinding.FragmentHelpListBinding
import com.nexbank.app.databinding.ItemHelpArticleBinding
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * LEGACY-STYLE module on purpose: real banking apps are mixed. This screen uses the "classic" stack:
 * XML layouts + Data Binding + LiveData + Fragments + Navigation Component (nav graph in res/navigation).
 */
data class HelpArticle(val id: String, val title: String, val body: String)

class HelpRepository @Inject constructor(private val config: CountryConfigProvider) {
    /** Flow of articles that re-emits when the user switches market. */
    fun articles() = config.config.map { cfg ->
        val common = listOf(
            HelpArticle("lost", "What if my card is lost?", "Block the card in the app (where available) or call the number on the back of your card."),
            HelpArticle("statements", "Where are my e-statements?", "Open E-Statements from Home. Downloaded files are encrypted on your device."),
        )
        common + when (cfg.country) {
            Country.UK -> listOf(HelpArticle("tracker", "How does Payment Tracker work?", "Track faster payments and see when funds are expected to arrive."))
            Country.MEXICO -> listOf(HelpArticle("declaration", "Why do I need a declaration?", "Mexican regulations require a signed declaration when reporting a lost or stolen card."))
            Country.INDIA -> listOf(HelpArticle("limits", "What are my daily limits?", "Daily transfer limits depend on your account type."))
        }
    }
}

@HiltViewModel
class HelpViewModel @Inject constructor(repo: HelpRepository) : ViewModel() {
    /** Flow -> LiveData bridge for the XML world. */
    val articles: LiveData<List<HelpArticle>> = repo.articles().asLiveData()
    private val _selected = MutableLiveData<HelpArticle?>()
    val selected: LiveData<HelpArticle?> = _selected

    fun select(id: String) { _selected.value = articles.value?.firstOrNull { it.id == id } }
}

@AndroidEntryPoint
class HelpCenterActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help_center)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        setupActionBarWithNavController(navHost.navController)
    }

    override fun onSupportNavigateUp(): Boolean =
        (supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment).navController.navigateUp() || super.onSupportNavigateUp()
}

@AndroidEntryPoint
class HelpListFragment : Fragment() {
    private val viewModel: HelpViewModel by activityViewModels()
    private var _binding: FragmentHelpListBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHelpListBinding.inflate(inflater, container, false)
        return _binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val adapter = HelpAdapter { article ->
            // Navigation Component: pass arguments with a Bundle.
            findNavController().navigate(R.id.action_list_to_detail, bundleOf("articleId" to article.id))
        }
        _binding!!.recycler.layoutManager = LinearLayoutManager(requireContext())
        _binding!!.recycler.adapter = adapter
        viewModel.articles.observe(viewLifecycleOwner, adapter::submitList)
    }

    override fun onDestroyView() { _binding = null; super.onDestroyView() } // avoid leaking the view hierarchy
}

@AndroidEntryPoint
class HelpDetailFragment : Fragment() {
    private val viewModel: HelpViewModel by activityViewModels()
    private var _binding: FragmentHelpDetailBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHelpDetailBinding.inflate(inflater, container, false).also {
            it.vm = viewModel                       // Data Binding: layout reads vm.selected.title / .body
            it.lifecycleOwner = viewLifecycleOwner  // lets the layout observe LiveData
        }
        return _binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireArguments().getString("articleId")?.let(viewModel::select)
        viewModel.articles.observe(viewLifecycleOwner) { requireArguments().getString("articleId")?.let(viewModel::select) }
    }

    override fun onDestroyView() { _binding = null; super.onDestroyView() }
}

class HelpAdapter(private val onClick: (HelpArticle) -> Unit) : ListAdapter<HelpArticle, HelpAdapter.VH>(Diff) {
    class VH(val binding: ItemHelpArticleBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(ItemHelpArticleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.binding.article = item
        holder.binding.root.setOnClickListener { onClick(item) }
        holder.binding.executePendingBindings()
    }

    private object Diff : DiffUtil.ItemCallback<HelpArticle>() {
        override fun areItemsTheSame(a: HelpArticle, b: HelpArticle) = a.id == b.id
        override fun areContentsTheSame(a: HelpArticle, b: HelpArticle) = a == b
    }
}
