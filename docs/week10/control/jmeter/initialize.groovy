import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

SampleResult.setIgnore()
def run = Path.of(props.getProperty('runDir'))
def telemetry = run.resolve('telemetry.csv')
def requests = run.resolve('order-requests.csv')
def manifest = Files.readAllLines(Path.of(props.getProperty('catalog')))
assert manifest.first().replace('\uFEFF', '') == 'productId,brandId,category,tier'
def catalog = [:]
manifest.tail().findAll { !it.isBlank() }.each { line ->
    def fields = line.split(',', -1)
    assert fields.size() == 4
    def (id, brand, category, tier) = fields.collect { it.trim() }
    assert id.isLong() && id.toLong() > 3 && brand.isLong() && !category.empty && tier in ['hot', 'warm', 'longtail']
    assert catalog.put(id, [id:id, brand:brand, category:category, tier:tier]) == null
}
assert !catalog.empty
props.put('control.catalog', catalog.asImmutable())
props.put('control.brands', catalog.values().groupBy { it.brand })
props.put('control.tiers', catalog.values().groupBy { it.tier })
props.put('control.checkout', Double.parseDouble(props.getProperty('checkout', '1.6')))
props.put('control.browseCounters', ['listReads','brandReads','detailReads','revisitReads','mixedSent',
    'hotViews','warmViews','longtailViews'].collectEntries { [(it):new LongAdder()] })
Files.createFile(requests)
Files.writeString(requests, 'timestampMs,kind,stage,productId,orderId,attemptKey,userId,cartItems\n')
Files.createFile(telemetry)
Files.writeString(telemetry, 'timestampMs,activeReaders,productRequests,productErrors,waterSent,processCpuPct,' +
    'listReads,brandReads,detailReads,revisitReads,mixedSent,hotViews,warmViews,longtailViews\n')
props.put('control.requests', requests)
props.put('control.telemetry', telemetry)
props.put('control.secondFixture', run.resolve('start-second-fixture'))
props.put('control.stop', run.resolve('stop-load'))
props.put('control.nextWaterNs', new AtomicLong(System.nanoTime()))
props.put('control.readCount', new LongAdder())
props.put('control.readErrors', new LongAdder())
props.put('control.waterSent', new LongAdder())
