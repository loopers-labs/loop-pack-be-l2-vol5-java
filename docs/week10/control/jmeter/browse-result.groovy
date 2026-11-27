import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.concurrent.ThreadLocalRandom

String action = vars.get('action')
prev.sampleLabel = 'browse-' + action
props.get('control.readCount').increment()
def counts = props.get('control.browseCounters')
counts[action + 'Reads'].increment()
def catalog = props.get('control.catalog')
try {
    if (prev.responseCode != '200') {
        throw new IllegalStateException('Expected HTTP 200')
    }
    def envelope = new JsonSlurper().parse(prev.responseData, 'UTF-8')
    if (envelope.meta?.result != 'SUCCESS') {
        throw new IllegalStateException('Expected a successful response envelope')
    }
    if (action in ['list', 'brand']) {
        if (!(envelope.data?.items instanceof List)) {
            throw new IllegalStateException('Expected a product list')
        }
        def listed = envelope.data.items.collect { catalog[it.productId.toString()] }.findAll { it != null }
        vars.putObject('listed', listed)
        return
    }
    String product = vars.get('productId')
    if (envelope.data?.productId?.toString() != product) {
        throw new IllegalStateException('Detail response does not identify the requested product')
    }
    def item = catalog[product]
    counts[item.tier + 'Views'].increment()
    def visited = vars.getObject('visited')
    visited.removeAll { it.id == product }
    visited << item
    if (visited.size() > 6) {
        visited.remove(0)
    }
    def random = ThreadLocalRandom.current()
    if (random.nextDouble(100) >= props.get('control.checkout')) {
        return
    }
    def cart = [[productId:product.toLong(), quantity:random.nextDouble() < 0.23 ? 2 : 1]]
    def related = (vars.getObject('listed') ?: []).findAll { it.id != product }
    if (related && random.nextDouble() < 0.30) {
        cart << [productId:related[random.nextInt(related.size())].id.toLong(), quantity:1]
    }
    vars.put('kind','mixed')
    vars.put('userId', vars.get('shopper'))
    vars.put('cartItems', cart.collect { it.productId.toString() + ':' + it.quantity }.join('|'))
    vars.put('cartBody', JsonOutput.toJson([items:cart]))
    vars.put('attemptKey', UUID.randomUUID().toString())
    vars.put('checkout', 'true')
} catch (Exception invalid) {
    if (action in ['list', 'brand']) { vars.putObject('listed', []) }
    props.get('control.readErrors').increment()
    prev.setSuccessful(false)
    prev.setResponseMessage(invalid.message)
}
