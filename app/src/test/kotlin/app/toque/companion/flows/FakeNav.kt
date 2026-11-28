package app.toque.companion.flows

import app.toque.companion.ui.Navigator
import app.toque.companion.ui.Route
import app.toque.companion.ui.Tab
import java.util.EnumMap

/** Navigation sans vues, avec les règles de la coquille (une pile par onglet). */
class FakeNav : Navigator {
    private val stacks = EnumMap<Tab, ArrayList<Route>>(Tab::class.java)
    private var tab = Tab.LIVE
    var pairing = false

    init {
        for (r in Route.values()) if (r.root) stacks.getOrPut(r.tab) { arrayListOf(r) }
    }

    fun stack(t: Tab): List<Route> = stacks.getValue(t).toList()

    override val current: Route get() = if (pairing) Route.APPAIRAGE else stacks.getValue(tab).last()
    override fun select(tab: Tab) {
        val s = stacks.getValue(tab)
        if (tab == this.tab) while (s.size > 1) s.removeAt(s.size - 1)
        this.tab = tab
    }
    override fun open(route: Route) {
        val s = stacks.getValue(route.tab)
        if (s.last() != route) s += route
        tab = route.tab
    }
    override fun replace(route: Route) {
        val s = stacks.getValue(route.tab)
        s.removeAll { it == route }
        if (s.size > 1) s[s.size - 1] = route else s += route
        tab = route.tab
    }
    override fun showLive(route: Route) {
        val s = stacks.getValue(Tab.LIVE)
        s.removeAll { it == route }
        if (s.isEmpty()) s += route else s[0] = route
    }
    override fun back(): Boolean {
        val s = stacks.getValue(tab)
        if (s.size > 1) { s.removeAt(s.size - 1); return true }
        if (tab != Tab.LIVE) { tab = Tab.LIVE; return true }
        return false
    }
    override fun close(route: Route) {
        val s = stacks.getValue(tab)
        if (current == route && s.size > 1) s.removeAt(s.size - 1)
    }
    override fun showPairing(visible: Boolean) { pairing = visible }
}
