# Rate limiter video script (about 5 minutes)

This is what I say and what I run, in order. Times are targets for the edited video. Each load
run is 20 to 30 seconds, and the scripts wait between heavy steps for the operating system to
free the previous step's closed connections, so I pause the recording (or cut) during those
waits and during start-up. Everything runs on the Week 6 event-driven version of the
self-checkout system, branch `rate-limiter`.

Two terminals side by side:
- **left:** the demo steps, `scripts/demo/<n>-*.sh`;
- **right:** `scripts/demo/watch.sh`, one line a second from the limiter itself
  (admitted, refused, how many refusals never reached Redis, fallback decisions, Redis status).

---

## 0:00 to 0:30 · What I built

**Show:** [docs/diagrams/rate-limiter.png](docs/diagrams/rate-limiter.png)

> This is my rate limiter on the Week 6 event-driven self-checkout system. Clients go through the
> nginx gateway. The limiter is a servlet filter that runs first in checkout-service, before the
> request body is even read. It's a token bucket stored in Redis, checked with one atomic Lua
> script, so every instance of a service shares one limit.
>
> By default I only limit starting a new transaction, 650 a second with a burst of 50. Scans and
> payments are never refused, for two reasons: refusing them throws away work the basket already
> did, and since checkout never expires an open basket and the load client abandons its basket on
> any error, every refused scan or payment would leak a basket. If Redis goes away, each process
> falls back to a local bucket, so the system keeps running.

## 0:30 to 0:45 · Start (run before recording, show the output)

```bash
scripts/demo/1-start.sh
```

> Everything is up: the gateway, the services, Redis, RabbitMQ. The limiter is loaded in each
> service but switched off for now. I can switch it live, without a restart.

Start `scripts/demo/watch.sh` in the right terminal.

## 0:45 to 1:30 · Overload with no limiter

```bash
scripts/demo/2-overload-no-limiter.sh 4000 20
```

> First, no limiter. The course load client with 4,000 stations. *(while it runs)* Throughput
> collapses. *(at the end)* About 47 transactions a second instead of 700; 85% of starts fail,
> mostly with no answer at all, and nginx says it ran out of worker connections. This is what
> breaking looks like on this laptop.

## 1:30 to 2:00 · Being honest about 4,000 stations

**Show:** [reports/rate-limiter/charts/throughput-and-failures.png](reports/rate-limiter/charts/throughput-and-failures.png)

> I measured the same 4,000 stations with the limiter on, and it doesn't rescue it with this
> client: 9 to 11 transactions a second. The collapse happens at the connection level, before
> requests reach my filter. Tomcat's accept backlog overflows and nginx runs out of local ports,
> and the course client retries every failure instantly, which keeps the flood going. With a
> client that backs off, the limiter does rescue it: 280 and 155 transactions a second in my two
> runs, with no failed transactions. So a limiter inside the service needs a connection limit at
> the edge in front of it; that's what I'd add next.

## 2:00 to 2:45 · With the limiter: the retry storm vs a polite client

```bash
scripts/demo/3-overload-with-limiter.sh 2000 20 course
```

> Now 2,000 stations, limiter on, still the course client. *(point at watch.sh)* Exactly 650 admitted
> every second while thousands get a 429. The limit is accurate. But look at the pay time: around
> 400 ms, no better than without the limiter. The course client retries every 429 instantly, so
> refused stations are back in the queue a millisecond later, and admitted customers wait behind
> them.

```bash
scripts/demo/3-overload-with-limiter.sh 2000 20 polite
```

> Same limiter, same load, but a client that respects the 429: it waits at least the hint the server
> gives, and backs off further if it's refused again. Still exactly 650 a second, zero errors, and
> the median time to pay is now about 10 ms instead of 400. My first version of this client slept
> exactly the millisecond hint, and all the waiting stations came back together; backoff fixed that.

## 2:45 to 3:10 · What a throttled client sees

```bash
scripts/demo/4-curl-429.sh
```

> A refused request, with curl. A 429, a JSON body, `Retry-After` in whole seconds as the standard
> requires, and `X-RateLimit-Retry-After-Ms`, which is my own non-standard header, because at 650 a
> second the real wait is about a millisecond and a whole second is far too long. Standard clients
> just ignore it. Admitted requests get the same rate-limit headers and are otherwise exactly what the
> API contract says.

## 3:10 to 3:35 · Distributed: two instances, one limit

```bash
scripts/demo/5-distributed.sh 15
```

Optional: `scripts/demo/watch.sh --catalog` in the right terminal.

> Two catalog instances behind the gateway, both with a 100 a second rule on the same Redis key. Each
> instance admits about half, and together they admit 101.5 a second, not 200. The limit is shared.

## 3:35 to 4:20 · Fault tolerance: Redis goes down

```bash
scripts/demo/6-redis-down.sh
```

> Load is running; after 10 seconds the script stops Redis. *(point at watch.sh)* Redis is DOWN, the
> fallback column takes over, and the admitted rate stays at 650. The client sees no errors, only 201s
> and 429s. *(Redis comes back)* About two seconds later the shared limit is back. The invariant check
> at the end still passes, so nothing was lost or oversold.

## 4:20 to 4:50 · The numbers

```bash
scripts/demo/7-numbers.sh
```

**Show also:** [reports/rate-limiter/charts/latency-p50.png](reports/rate-limiter/charts/latency-p50.png)

> All from 60-second runs, twice each, on an idle machine. The admitted rate is on 650 at every
> load. When nothing is refused, the check adds about a sixth of a millisecond to the median
> request. Redis holds one small hash per rule, about 120 bytes each, and used about 5% of a core
> at the worst storm.

## 4:50 to 5:00 · Wrap up

> So: accurate, shared across instances, cheap, and it survives losing Redis. Admitted customers stay
> fast when clients respect the 429, and for floods that exhaust connections, the next step is a
> connection limit at the edge. The write-up is in RATE_LIMITER.md.

---

## Recording checklist

Before:
- [ ] Mac on the charger, other apps closed, `uptime` load below 3 (`sysctl -n vm.loadavg`).
- [ ] PostgreSQL, RabbitMQ and Redis running: `brew services list`.
- [ ] Jars built: `scripts/start-all.sh --build` once, then `scripts/stop-all.sh`.
- [ ] Course load client compiled: `cd ../CS6510-2026/load-client && bash build.sh`.
- [ ] Terminal font large enough to read (the step 3 and step 7 tables are about 110 columns wide).
- [ ] `scripts/demo/1-start.sh` run just before recording (it resets the databases).

During:
- [ ] Pause or cut while a script prints "waiting for the previous step's ... closed connections".
- [ ] If a run looks very different from the numbers here, rerun that step after the wait; don't
      record a run that started while the machine was still busy.
- [ ] Keep `watch.sh` visible during steps 2, 3 and 6.

After:
- [ ] `scripts/stop-all.sh`.
- [ ] Demo output goes to `reports/rate-limiter/demo/`, which git ignores.

What to expect (from the dry run on this machine):

| Step | Takes | Shows |
|---|---|---|
| 1 | about 11 s | all healthy, limiter off |
| 2 | about 40 s for a 30 s run | 47 tx/s, 85% of starts fail, nginx out of worker connections |
| 3 course, 2,000 | about 35 s for 30 s, plus up to 30 s waiting | 621 tx/s, admitted 650/s, pay p50 about 420 ms |
| 3 polite, 2,000 | about 35 s for 30 s, plus up to 30 s waiting | 654 tx/s, 0 errors, pay p50 about 10 ms |
| 4 | about 10 s plus waiting | the 429 with its headers and body |
| 5 | about 16 s | 101.5/s across two instances |
| 6 | about 40 s plus waiting | 652 to 672/s admitted through the outage, 0 errors, invariant PASS |
| 7 | instant | the tables |
