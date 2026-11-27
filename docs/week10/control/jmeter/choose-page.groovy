import java.util.concurrent.ThreadLocalRandom

SampleResult.setIgnore()
def random = ThreadLocalRandom.current()
if (!vars.get('shopper')) {
    vars.put('shopper', (2001 + ctx.threadNum % 400).toString())
    vars.putObject('visited', [])
    if (ctx.threadNum == 0) {
        props.put('control.readerGroup', ctx.threadGroup)
    }
}
['productId','brandId','category','tier','orderId','attemptKey','cartItems'].each { vars.put(it, '') }
vars.put('checkout', 'false')
String previous = vars.get('action') ?: ''
def listed = vars.getObject('listed') ?: []
def visited = vars.getObject('visited')
boolean wasListing = previous in ['list', 'brand']
double choice = random.nextDouble()
def selected
String action
if (!wasListing && visited && choice < 0.22) {
    action = 'revisit'
    selected = visited[random.nextInt(visited.size())]
} else if (listed && choice < (wasListing ? 0.79 : 0.49)) {
    action = 'detail'
    // Select only from the last real list response. Within it, popular products attract more views.
    def weights = listed.collect { it.tier == 'hot' ? 20 : it.tier == 'warm' ? 5 : 1 }
    int ticket = random.nextInt(weights.sum() as int)
    for (int i = 0; i < listed.size(); i++) {
        ticket -= weights[i]
        if (ticket < 0) {
            selected = listed[i]
            break
        }
    }
}
if (selected) {
    vars.put('productId', selected.id)
    vars.put('brandId', selected.brand)
    vars.put('category', selected.category)
    vars.put('tier', selected.tier)
    vars.put('browsePath', '/api/v1/products/' + selected.id)
} else {
    def catalog = props.get('control.catalog')
    def byBrand = props.get('control.brands')
    action = random.nextDouble() < 0.32 ? 'brand' : 'list'
    int size = random.nextDouble() < 0.7 ? 20 : 10
    int available = catalog.size() + 3
    String brandFilter = ''
    if (action == 'brand') {
        double taste = random.nextDouble()
        def byTier = props.get('control.tiers')
        def pool = byTier[taste < 0.55 ? 'hot' : taste < 0.85 ? 'warm' : 'longtail'] ?: catalog.values().toList()
        String brand = visited && random.nextBoolean() ? visited.last().brand : pool[random.nextInt(pool.size())].brand
        brandFilter = '&brandId=' + brand
        vars.put('brandId', brand)
        available = byBrand[brand].size()
    }
    int pages = (available + size - 1).intdiv(size)
    double depth = random.nextDouble()
    int page = depth < 0.53 ? 0 : depth < 0.74 ? Math.min(1, pages - 1) : random.nextInt(pages)
    double sort = random.nextDouble()
    String order = sort < 0.45 ? 'latest' : sort < 0.72 ? 'price_asc' : 'likes_desc'
    vars.put('browsePath', '/api/v1/products?sort=' + order + '&page=' + page + '&size=' + size + brandFilter)
}
vars.put('action', action)
