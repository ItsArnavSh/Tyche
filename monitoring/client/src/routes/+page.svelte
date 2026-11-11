<script lang="ts">
	import { onMount } from 'svelte';

	interface Portfolio {
		initial_cash: number;
		cash: number;
		market_value: number;
		equity: number;
		realised_pnl: number;
		unrealised_pnl: number;
		total_pnl: number;
		total_pnl_pct: number;
		fees_paid: number;
		buys: number;
		sells: number;
		wins: number;
		losses: number;
	}

	interface Position {
		ticker: string;
		quantity: number;
		entry_price: number;
		current_price: number;
		market_value: number;
		unrealised_pnl: number;
		unrealised_pnl_pct: number;
		stop_loss: number;
		take_profit: number;
		confidence: number;
		reason: string;
		opened_at: string;
		sell_after: string;
		aggregate_confidence: number;
		peak_confidence: number;
		signal_count: number;
	}

	interface ClosedTrade {
		ticker: string;
		quantity: number;
		entry: number;
		exit: number;
		pnl: number;
		pnl_pct: number;
		reason: string;
		opened_at: string;
		closed_at: string;
		duration_seconds: number;
	}

	interface Decision {
		id: number;
		at: string;
		ticker: string;
		action: string;
		quantity: number;
		price: number;
		confidence: number;
		reason: string;
		status: string;
		error?: string;
	}

	interface Signal {
		at: string;
		ticker: string;
		strategy: string;
		confidence: number;
		size: string;
		expires_at: string;
	}

	interface MarketSymbol {
		ticker: string;
		price: number;
		aggregate_confidence: number;
		peak_confidence: number;
		signal_count: number;
		has_position: boolean;
	}

	interface Feed {
		source: string;
		universe?: string;
		symbols: string[];
		roll_interval_ms: number;
		core_connected: boolean;
		redis_connected: boolean;
		last_roll_at?: string;
		last_boot_at?: string;
		last_roll_updates: number;
		missing: number;
		missing_tickers: string[];
		missing_series: string[];
		last_roll_error?: string;
		last_error?: string;
		last_error_scope?: string;
		last_reboot_at?: string;
		reboot_attempts: number;
		reboot_succeeded: number;
		reboot_failed: number;
		reboot_in_flight: number;
		reboot_pending: number;
		reboot_permanent: number;
		reboot_permanent_series: string[];
	}

	interface Stats {
		active_positions: number;
		max_positions: number;
		closed_trades: number;
		wins: number;
		losses: number;
		signals_received: number;
		orders: number;
		fills: number;
		rejections: number;
		bucket_tickers: number;
	}

	interface Risk {
		min_confidence: number;
		max_positions: number;
		max_position_fraction: number;
		stop_loss_percent: number;
		take_profit_percent: number;
		hold_duration_ms: number;
		rebuy_cooldown_ms: number;
	}

	interface Snapshot {
		generated_at: string;
		uptime_seconds: number;
		mode: string;
		state: string;
		portfolio: Portfolio;
		positions: Position[];
		closed_trades: ClosedTrade[];
		decisions: Decision[];
		signals: Signal[];
		market: MarketSymbol[];
		feed: Feed;
		stats: Stats;
		risk: Risk;
		errors?: { at: string; scope: string; error: string }[];
	}

	const DEFAULT_API = 'http://localhost:8080/api/status';
	const POLL_MS = 2000;
	const MAX_HISTORY = 60;

	let data = $state<Snapshot | null>(null);
	let live = $state(false);
	let loading = $state(true);
	let error = $state<string | null>(null);
	let lastUpdated = $state<Date | null>(null);
	let now = $state(Date.now());
	let apiURL = $state(DEFAULT_API);
	let equityHistory = $state<number[]>([]);
	let timer: ReturnType<typeof setInterval> | undefined;

	const portfolio = $derived(data?.portfolio ?? null);
	const positions = $derived(data?.positions ?? []);
	const decisions = $derived(data?.decisions ?? []);
	const closedTrades = $derived(data?.closed_trades ?? []);
	const signals = $derived(data?.signals ?? []);
	const market = $derived(data?.market ?? []);
	const feed = $derived(data?.feed ?? null);
	const stats = $derived(data?.stats ?? null);
	const risk = $derived(data?.risk ?? null);

	const spark = $derived.by(() => {
		const values = equityHistory;
		if (values.length < 2) return { points: '', area: '' };
		const width = 620;
		const height = 92;
		const pad = 4;
		const min = Math.min(...values);
		const max = Math.max(...values);
		const span = max - min || 1;
		const points = values
			.map((value, index) => {
				const x = pad + (index / (values.length - 1)) * (width - pad * 2);
				const y = height - pad - ((value - min) / span) * (height - pad * 2);
				return `${x.toFixed(1)},${y.toFixed(1)}`;
			})
			.join(' ');
		return {
			points,
			area: `${pad},${height - pad} ${points} ${width - pad},${height - pad}`
		};
	});

	const winRate = $derived.by(() => {
		if (!portfolio) return 0;
		const total = portfolio.wins + portfolio.losses;
		return total > 0 ? (portfolio.wins / total) * 100 : 0;
	});

	async function refresh() {
		try {
			const response = await fetch(apiURL, { cache: 'no-store' });
			if (!response.ok) throw new Error(`status API returned ${response.status}`);
			const next = (await response.json()) as Snapshot;
			data = next;
			live = true;
			error = null;
			lastUpdated = new Date();
			now = Date.now();
			equityHistory = [...equityHistory, next.portfolio.equity].slice(-MAX_HISTORY);
		} catch (err) {
			live = false;
			error = err instanceof Error ? err.message : 'could not reach the status API';
		} finally {
			loading = false;
		}
	}

	onMount(() => {
		const requested = new URLSearchParams(window.location.search).get('api');
		apiURL =
			requested ??
			`${window.location.protocol}//${window.location.hostname}:8080/api/status`;
		refresh();
		timer = setInterval(refresh, POLL_MS);
		return () => clearInterval(timer);
	});

	function money(value: number | null | undefined, digits = 0) {
		if (value === null || value === undefined || Number.isNaN(value)) return '—';
		return new Intl.NumberFormat('en-IN', {
			style: 'currency',
			currency: 'INR',
			minimumFractionDigits: digits,
			maximumFractionDigits: digits
		}).format(value);
	}

	function number(value: number | null | undefined, digits = 2) {
		if (value === null || value === undefined || Number.isNaN(value)) return '—';
		return new Intl.NumberFormat('en-IN', {
			minimumFractionDigits: 0,
			maximumFractionDigits: digits
		}).format(value);
	}

	function price(value: number | null | undefined) {
		return number(value, 2);
	}

	function pct(value: number | null | undefined, digits = 2) {
		if (value === null || value === undefined || Number.isNaN(value)) return '—';
		return `${value > 0 ? '+' : ''}${value.toFixed(digits)}%`;
	}

	function confidence(value: number | null | undefined) {
		if (value === null || value === undefined || Number.isNaN(value)) return '—';
		return `${(value * 100).toFixed(1)}%`;
	}

	function displayTicker(ticker: string) {
		return ticker.replace(/\.NS$/, '');
	}

	function tone(value: number | null | undefined) {
		if (value === null || value === undefined || value === 0) return 'text-white/55';
		return value > 0 ? 'text-emerald-400' : 'text-rose-400';
	}

	function ago(iso: string | null | undefined) {
		if (!iso) return '—';
		const at = Date.parse(iso);
		if (Number.isNaN(at)) return '—';
		const seconds = Math.max(0, Math.floor((now - at) / 1000));
		if (seconds < 5) return 'just now';
		if (seconds < 60) return `${seconds}s ago`;
		const minutes = Math.floor(seconds / 60);
		if (minutes < 60) return `${minutes}m ago`;
		const hours = Math.floor(minutes / 60);
		if (hours < 24) return `${hours}h ago`;
		return `${Math.floor(hours / 24)}d ago`;
	}

	function clock(iso: string | null | undefined) {
		if (!iso) return '—';
		const date = new Date(iso);
		if (Number.isNaN(date.getTime())) return '—';
		return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
	}

	function duration(seconds: number | null | undefined) {
		if (!seconds || seconds < 0) return '—';
		if (seconds < 60) return `${Math.floor(seconds)}s`;
		const minutes = Math.floor(seconds / 60);
		if (minutes < 60) return `${minutes}m`;
		const hours = Math.floor(minutes / 60);
		return `${hours}h ${minutes % 60}m`;
	}

	function uptime(seconds: number | null | undefined) {
		if (!seconds || seconds < 0) return '—';
		const hours = Math.floor(seconds / 3600);
		const minutes = Math.floor((seconds % 3600) / 60);
		if (hours > 0) return `${hours}h ${minutes}m`;
		if (minutes > 0) return `${minutes}m ${seconds % 60}s`;
		return `${seconds}s`;
	}
</script>

<svelte:head>
	<title>Tyche — Live Paper Trading</title>
	<meta
		name="description"
		content="Live decisions, fills, positions and portfolio state from the Tyche Go gateway."
	/>
</svelte:head>

<div class="min-h-screen bg-[#08090c] text-white">
	<div
		class="pointer-events-none fixed inset-0 bg-[radial-gradient(ellipse_at_top,rgba(52,211,153,0.07),transparent_45%),radial-gradient(ellipse_at_bottom_right,rgba(129,140,248,0.06),transparent_40%)]"
	></div>

	<div class="relative mx-auto max-w-[1680px] px-4 py-5 sm:px-6 lg:px-8">
		<header
			class="flex flex-col gap-4 border-b border-white/[0.06] pb-5 sm:flex-row sm:items-center sm:justify-between"
		>
			<div class="flex items-center gap-3">
				<div
					class="flex h-9 w-9 items-center justify-center rounded-xl border border-emerald-400/20 bg-emerald-400/10"
				>
					<span class="text-sm font-semibold tracking-tight text-emerald-300">T</span>
				</div>
				<div>
					<div class="flex items-center gap-2">
						<h1 class="text-sm font-semibold tracking-[0.18em] text-white/85 uppercase">Tyche</h1>
						<span
							class="rounded-full border border-amber-400/20 bg-amber-400/10 px-2 py-0.5 text-[9px] font-medium tracking-[0.14em] text-amber-300 uppercase"
							>paper</span
						>
					</div>
					<p class="mt-0.5 text-[11px] text-white/35">
						Autonomous market data → signals → risk → fills
					</p>
				</div>
			</div>

			<div class="flex flex-wrap items-center gap-x-5 gap-y-2 text-[11px] text-white/35">
				<div class="flex items-center gap-2">
					<span
						class="h-1.5 w-1.5 rounded-full {live
							? 'animate-pulse bg-emerald-400 shadow-[0_0_10px_rgba(52,211,153,0.8)]'
							: 'bg-rose-400'}"
					></span>
					<span class={live ? 'text-emerald-300' : 'text-rose-300'}>
						{live ? 'live' : loading ? 'connecting' : 'offline'}
					</span>
				</div>
				<span class="hidden h-3 w-px bg-white/10 sm:block"></span>
				<span class="tabular-nums">{lastUpdated ? lastUpdated.toLocaleTimeString() : '—'}</span>
				<span class="rounded-md border border-white/[0.06] bg-white/[0.03] px-2 py-1 tabular-nums"
					>up {uptime(data?.uptime_seconds)}</span
				>
				<button
					type="button"
					onclick={refresh}
					class="rounded-md border border-white/[0.08] bg-white/[0.04] px-2.5 py-1 text-white/60 transition hover:border-white/20 hover:text-white"
				>
					refresh
				</button>
			</div>
		</header>

		{#if error}
			<div
				class="mt-4 flex items-center justify-between gap-4 rounded-xl border border-rose-400/20 bg-rose-400/[0.06] px-4 py-3 text-xs text-rose-200"
			>
				<span>Status feed unavailable: {error}</span>
				<span class="font-mono text-[10px] text-rose-200/50">{apiURL}</span>
			</div>
		{/if}

		<main class="mt-5 space-y-5">
			<!-- portfolio strip -->
			<section class="grid grid-cols-2 gap-3 lg:grid-cols-6">
				<div
					class="col-span-2 flex flex-col justify-between gap-3 rounded-2xl border border-white/[0.06] bg-[#0e1016]/90 p-4 lg:col-span-2 lg:row-span-2"
				>
					<div class="flex items-start justify-between">
						<div>
							<p class="text-[10px] font-medium tracking-[0.18em] text-white/30 uppercase">
								equity
							</p>
							<p class="mt-1 text-3xl font-medium tracking-tight tabular-nums">
								{money(portfolio?.equity, 2)}
							</p>
						</div>
						<span
							class="rounded-full px-2.5 py-1 text-[11px] font-medium tabular-nums {portfolio &&
							portfolio.total_pnl >= 0
								? 'bg-emerald-400/10 text-emerald-300'
								: 'bg-rose-400/10 text-rose-300'}"
						>
							{pct(portfolio?.total_pnl_pct)}
						</span>
					</div>

					<div class="relative h-[98px] w-full">
						{#if spark.points}
							<svg viewBox="0 0 620 92" class="h-full w-full overflow-visible" preserveAspectRatio="none">
								<polygon points={spark.area} fill="rgba(52,211,153,0.09)"></polygon>
								<polyline
									points={spark.points}
									fill="none"
									stroke="rgba(52,211,153,0.85)"
									stroke-width="2"
									stroke-linejoin="round"
									stroke-linecap="round"
								></polyline>
							</svg>
						{:else}
							<div
								class="flex h-full items-center justify-center rounded-lg border border-dashed border-white/[0.06] text-[10px] text-white/20"
							>
								waiting for equity history
							</div>
						{/if}
					</div>

					<div class="flex items-center justify-between text-[10px] text-white/30">
						<span>start {money(portfolio?.initial_cash)}</span>
						<span class="tabular-nums {tone(portfolio?.total_pnl)}">
							{tone(portfolio?.total_pnl) === 'text-emerald-400'
								? '+'
								: tone(portfolio?.total_pnl) === 'text-rose-400'
									? ''
									: ''}{money(portfolio?.total_pnl, 2)}
						</span>
					</div>
				</div>

				{#each [
					{ label: 'cash', value: money(portfolio?.cash, 2), sub: `${portfolio?.buys ?? 0} buys · ${portfolio?.sells ?? 0} sells` },
					{ label: 'total p/l', value: money(portfolio?.total_pnl, 2), sub: `${pct(portfolio?.total_pnl_pct)} on initial`, tone: tone(portfolio?.total_pnl) },
					{ label: 'realised p/l', value: money(portfolio?.realised_pnl, 2), sub: `fees ${money(portfolio?.fees_paid, 2)}`, tone: tone(portfolio?.realised_pnl) },
					{ label: 'unrealised p/l', value: money(portfolio?.unrealised_pnl, 2), sub: 'open positions marked live', tone: tone(portfolio?.unrealised_pnl) }
				] as card}
					<div class="rounded-2xl border border-white/[0.06] bg-[#0e1016]/90 p-4">
						<p class="text-[10px] font-medium tracking-[0.18em] text-white/30 uppercase">
							{card.label}
						</p>
						<p class="mt-2 text-lg font-medium tabular-nums {card.tone ?? 'text-white/85'}">
							{card.value}
						</p>
						<p class="mt-1 text-[10px] text-white/25">{card.sub}</p>
					</div>
				{/each}

				<div class="rounded-2xl border border-white/[0.06] bg-[#0e1016]/90 p-4">
					<p class="text-[10px] font-medium tracking-[0.18em] text-white/30 uppercase">win rate</p>
					<div class="mt-2 flex items-baseline gap-2">
						<p class="text-lg font-medium tabular-nums text-white/85">{winRate.toFixed(1)}%</p>
						<p class="text-[10px] text-white/25">{portfolio?.wins ?? 0}W / {portfolio?.losses ?? 0}L</p>
					</div>
					<div class="mt-3 h-1 overflow-hidden rounded-full bg-white/[0.05]">
						<div
							class="h-full rounded-full bg-emerald-400/70 transition-all duration-700"
							style="width:{Math.min(winRate, 100)}%"
						></div>
					</div>
				</div>

				<div class="rounded-2xl border border-white/[0.06] bg-[#0e1016]/90 p-4">
					<p class="text-[10px] font-medium tracking-[0.18em] text-white/30 uppercase">slots</p>
					<div class="mt-2 flex items-baseline gap-2">
						<p class="text-lg font-medium tabular-nums text-white/85">
							{stats?.active_positions ?? 0}<span class="text-xs text-white/25"
								>/{stats?.max_positions ?? 0}</span
							>
						</p>
						<p class="text-[10px] text-white/25">open positions</p>
					</div>
					<div class="mt-3 flex gap-1">
						{#each Array(stats?.max_positions ?? 0) as _, index}
							<span
								class="h-1 flex-1 rounded-full {index < (stats?.active_positions ?? 0)
									? 'bg-indigo-400/80'
									: 'bg-white/[0.06]'}"
							></span>
						{/each}
					</div>
				</div>
			</section>

			<div class="grid grid-cols-1 gap-5 xl:grid-cols-[minmax(0,1fr)_360px]">
				<div class="min-w-0 space-y-5">
					<!-- open positions -->
					<section class="overflow-hidden rounded-2xl border border-white/[0.06] bg-[#0e1016]/90">
						<div class="flex items-center justify-between border-b border-white/[0.05] px-4 py-3">
							<div class="flex items-center gap-2">
								<h2 class="text-[10px] font-medium tracking-[0.18em] text-white/35 uppercase">
									open positions
								</h2>
								<span class="rounded-full bg-white/[0.05] px-2 py-0.5 text-[10px] tabular-nums text-white/35"
									>{positions.length}</span
								>
							</div>
							<span class="text-[10px] text-white/20">marked from the live gateway feed</span>
						</div>

						{#if positions.length === 0}
							<div class="flex flex-col items-center gap-1 px-4 py-10 text-center">
								<p class="text-sm text-white/45">No open positions</p>
								<p class="text-[11px] text-white/20">
									Waiting for an aggregate signal above {confidence(risk?.min_confidence)}.
								</p>
							</div>
						{:else}
							<div class="overflow-x-auto">
								<table class="w-full min-w-[820px] text-left">
									<thead>
										<tr class="text-[9px] tracking-[0.14em] text-white/25 uppercase">
											<th class="px-4 py-2 font-medium">symbol</th>
											<th class="px-3 py-2 text-right font-medium">qty</th>
											<th class="px-3 py-2 text-right font-medium">entry</th>
											<th class="px-3 py-2 text-right font-medium">current</th>
											<th class="px-3 py-2 text-right font-medium">value</th>
											<th class="px-3 py-2 text-right font-medium">p/l</th>
											<th class="px-3 py-2 text-right font-medium">stop / target</th>
											<th class="px-3 py-2 text-right font-medium">confidence</th>
											<th class="px-4 py-2 text-right font-medium">opened</th>
										</tr>
									</thead>
									<tbody>
										{#each positions as position (position.ticker)}
											<tr class="border-t border-white/[0.045] text-xs">
												<td class="px-4 py-3">
													<div class="flex items-center gap-2">
														<span class="font-medium text-white/80">{displayTicker(position.ticker)}</span>
														<span class="text-[9px] text-white/20">{position.ticker.endsWith('.NS') ? '.NS' : ''}</span>
													</div>
													<p class="mt-0.5 text-[10px] text-white/25">
														{position.signal_count} signal{position.signal_count === 1 ? '' : 's'} live
													</p>
												</td>
												<td class="px-3 py-3 text-right tabular-nums text-white/60">{position.quantity}</td>
												<td class="px-3 py-3 text-right tabular-nums text-white/60">{price(position.entry_price)}</td>
												<td class="px-3 py-3 text-right tabular-nums text-white/80">{price(position.current_price)}</td>
												<td class="px-3 py-3 text-right tabular-nums text-white/60">{money(position.market_value, 2)}</td>
												<td class="px-3 py-3 text-right tabular-nums {tone(position.unrealised_pnl)}">
													<div>{money(position.unrealised_pnl, 2)}</div>
													<div class="text-[10px] opacity-70">{pct(position.unrealised_pnl_pct)}</div>
												</td>
												<td class="px-3 py-3 text-right tabular-nums text-white/35">
													<div>{price(position.stop_loss)}</div>
													<div class="text-[10px] text-white/20">{price(position.take_profit)}</div>
												</td>
												<td class="px-3 py-3 text-right">
													<div class="flex items-center justify-end gap-2">
														<div class="h-1 w-12 overflow-hidden rounded-full bg-white/[0.06]">
															<div
																class="h-full rounded-full bg-indigo-400/70"
																style="width:{Math.min(Math.max(position.aggregate_confidence * 100, 0), 100)}%"
															></div>
														</div>
														<span class="text-[10px] tabular-nums text-white/40"
															>{confidence(position.aggregate_confidence)}</span
														>
													</div>
												</td>
												<td class="px-4 py-3 text-right text-[10px] tabular-nums text-white/30">
													{ago(position.opened_at)}
												</td>
											</tr>
										{/each}
									</tbody>
								</table>
							</div>
						{/if}
					</section>

					<!-- decisions / fills -->
					<section class="overflow-hidden rounded-2xl border border-white/[0.06] bg-[#0e1016]/90">
						<div class="flex items-center justify-between border-b border-white/[0.05] px-4 py-3">
							<div class="flex items-center gap-2">
								<h2 class="text-[10px] font-medium tracking-[0.18em] text-white/35 uppercase">
									decision &amp; fill log
								</h2>
								<span class="text-[10px] text-white/20">newest first</span>
							</div>
							<span class="text-[10px] tabular-nums text-white/25"
								>{stats?.orders ?? 0} attempts · {stats?.fills ?? 0} fills · {stats?.rejections ?? 0}
								rejected</span
							>
						</div>

						{#if decisions.length === 0}
							<div class="px-4 py-10 text-center">
								<p class="text-sm text-white/45">No decisions yet</p>
								<p class="mt-1 text-[11px] text-white/20">
									The decision layer evaluates live signals every few seconds.
								</p>
							</div>
						{:else}
							<div class="max-h-[360px] overflow-auto">
								<table class="w-full min-w-[760px] text-left">
									<thead class="sticky top-0 bg-[#0e1016]">
										<tr class="text-[9px] tracking-[0.14em] text-white/25 uppercase">
											<th class="px-4 py-2 font-medium">time</th>
											<th class="px-3 py-2 font-medium">action</th>
											<th class="px-3 py-2 font-medium">symbol</th>
											<th class="px-3 py-2 text-right font-medium">qty</th>
											<th class="px-3 py-2 text-right font-medium">price</th>
											<th class="px-3 py-2 text-right font-medium">conf</th>
											<th class="px-3 py-2 font-medium">status</th>
											<th class="px-4 py-2 font-medium">reason</th>
										</tr>
									</thead>
									<tbody>
										{#each decisions as decision (decision.id)}
											<tr class="border-t border-white/[0.045] text-xs">
												<td class="px-4 py-2.5 text-[10px] tabular-nums text-white/30">{clock(decision.at)}</td>
												<td class="px-3 py-2.5">
													<span
														class="rounded px-1.5 py-0.5 text-[9px] font-medium tracking-[0.1em] uppercase {decision.action ===
														'BUY'
															? 'bg-emerald-400/10 text-emerald-300'
															: 'bg-rose-400/10 text-rose-300'}"
													>
														{decision.action}
													</span>
												</td>
												<td class="px-3 py-2.5 font-medium text-white/70">{displayTicker(decision.ticker)}</td>
												<td class="px-3 py-2.5 text-right tabular-nums text-white/45">{decision.quantity}</td>
												<td class="px-3 py-2.5 text-right tabular-nums text-white/45">{price(decision.price)}</td>
												<td class="px-3 py-2.5 text-right tabular-nums text-white/35">{confidence(decision.confidence)}</td>
												<td class="px-3 py-2.5">
													<span
														class="rounded px-1.5 py-0.5 text-[9px] font-medium tracking-[0.08em] uppercase {decision.status ===
														'filled'
															? 'bg-white/[0.05] text-white/45'
															: 'bg-rose-400/10 text-rose-300'}"
													>
														{decision.status}
													</span>
												</td>
												<td class="max-w-[280px] truncate px-4 py-2.5 text-[10px] text-white/30" title={decision.error ?? decision.reason}>
													{decision.error ?? decision.reason}
												</td>
											</tr>
										{/each}
									</tbody>
								</table>
							</div>
						{/if}
					</section>

					<!-- closed trades -->
					<section class="overflow-hidden rounded-2xl border border-white/[0.06] bg-[#0e1016]/90">
						<div class="flex items-center justify-between border-b border-white/[0.05] px-4 py-3">
							<div class="flex items-center gap-2">
								<h2 class="text-[10px] font-medium tracking-[0.18em] text-white/35 uppercase">
									closed trades
								</h2>
								<span class="text-[10px] text-white/20">round trips</span>
							</div>
							<span class="text-[10px] tabular-nums text-white/25"
								>{stats?.closed_trades ?? 0} total · {stats?.wins ?? 0}W / {stats?.losses ?? 0}L</span
							>
						</div>

						{#if closedTrades.length === 0}
							<div class="px-4 py-10 text-center">
								<p class="text-sm text-white/45">No closed trades</p>
								<p class="mt-1 text-[11px] text-white/20">
									Exits appear here when a target, stop, hold limit or signal collapse fires.
								</p>
							</div>
						{:else}
							<div class="max-h-[280px] overflow-auto">
								<table class="w-full min-w-[700px] text-left">
									<thead class="sticky top-0 bg-[#0e1016]">
										<tr class="text-[9px] tracking-[0.14em] text-white/25 uppercase">
											<th class="px-4 py-2 font-medium">symbol</th>
											<th class="px-3 py-2 text-right font-medium">qty</th>
											<th class="px-3 py-2 text-right font-medium">entry → exit</th>
											<th class="px-3 py-2 text-right font-medium">p/l</th>
											<th class="px-3 py-2 font-medium">reason</th>
											<th class="px-3 py-2 text-right font-medium">held</th>
											<th class="px-4 py-2 text-right font-medium">closed</th>
										</tr>
									</thead>
									<tbody>
										{#each closedTrades as trade (trade.closed_at + trade.ticker)}
											<tr class="border-t border-white/[0.045] text-xs">
												<td class="px-4 py-2.5 font-medium text-white/70">{displayTicker(trade.ticker)}</td>
												<td class="px-3 py-2.5 text-right tabular-nums text-white/40">{trade.quantity}</td>
												<td class="px-3 py-2.5 text-right tabular-nums text-white/45">
													{price(trade.entry)} <span class="text-white/20">→</span> {price(trade.exit)}
												</td>
												<td class="px-3 py-2.5 text-right tabular-nums {tone(trade.pnl)}">
													<div>{money(trade.pnl, 2)}</div>
													<div class="text-[10px] opacity-70">{pct(trade.pnl_pct)}</div>
												</td>
												<td class="px-3 py-2.5 text-[10px] text-white/35">{trade.reason}</td>
												<td class="px-3 py-2.5 text-right text-[10px] tabular-nums text-white/30">{duration(trade.duration_seconds)}</td>
												<td class="px-4 py-2.5 text-right text-[10px] tabular-nums text-white/30">{ago(trade.closed_at)}</td>
											</tr>
										{/each}
									</tbody>
								</table>
							</div>
						{/if}
					</section>
				</div>

				<!-- right rail -->
				<aside class="space-y-5">
					<section class="rounded-2xl border border-white/[0.06] bg-[#0e1016]/90 p-4">
						<div class="flex items-center justify-between">
							<h2 class="text-[10px] font-medium tracking-[0.18em] text-white/35 uppercase">
								system health
							</h2>
							<span class="text-[10px] text-white/20">{feed?.source ?? '—'}{feed?.universe ? ` · ${feed.universe}` : ''}</span>
						</div>
						<div class="mt-3 space-y-2">
							<div class="flex items-center justify-between text-xs">
								<span class="text-white/40">gateway</span>
								<span class="flex items-center gap-1.5 {live ? 'text-emerald-300' : 'text-rose-300'}">
									<span class="h-1.5 w-1.5 rounded-full {live ? 'bg-emerald-400' : 'bg-rose-400'}"></span>
									{live ? 'serving' : 'unreachable'}
								</span>
							</div>
							<div class="flex items-center justify-between text-xs">
								<span class="text-white/40">engine</span>
								<span
									class="flex items-center gap-1.5 {data?.state === 'running'
										? 'text-emerald-300'
										: data?.state === 'degraded'
											? 'text-rose-300'
											: 'text-amber-300'}"
								>
									<span
										class="h-1.5 w-1.5 rounded-full {data?.state === 'running'
											? 'bg-emerald-400'
											: data?.state === 'degraded'
												? 'bg-rose-400'
												: 'bg-amber-400'}"
									></span>
									{data?.state ?? '—'}
								</span>
							</div>
							<div class="flex items-center justify-between text-xs">
								<span class="text-white/40">java core</span>
								<span class="flex items-center gap-1.5 {feed?.core_connected ? 'text-emerald-300' : 'text-rose-300'}">
									<span class="h-1.5 w-1.5 rounded-full {feed?.core_connected ? 'bg-emerald-400' : 'bg-rose-400'}"></span>
									{feed?.core_connected ? 'connected' : 'disconnected'}
								</span>
							</div>
							<div class="flex items-center justify-between text-xs">
								<span class="text-white/40">redis</span>
								<span class="flex items-center gap-1.5 {feed?.redis_connected ? 'text-emerald-300' : 'text-rose-300'}">
									<span class="h-1.5 w-1.5 rounded-full {feed?.redis_connected ? 'bg-emerald-400' : 'bg-rose-400'}"></span>
									{feed?.redis_connected ? 'connected' : 'disconnected'}
								</span>
							</div>
							<div class="my-3 h-px bg-white/[0.05]"></div>
							<div class="grid grid-cols-2 gap-x-3 gap-y-2 text-[10px]">
								<div>
									<p class="text-white/25">last roll</p>
									<p class="mt-0.5 tabular-nums text-white/55">{ago(feed?.last_roll_at)}</p>
								</div>
								<div class="text-right">
									<p class="text-white/25">roll updates</p>
									<p class="mt-0.5 tabular-nums text-white/55">{feed?.last_roll_updates ?? 0}</p>
								</div>
								<div>
									<p class="text-white/25">universe</p>
									<p class="mt-0.5 tabular-nums text-white/55">{feed?.symbols.length ?? 0} symbols</p>
								</div>
								<div class="text-right">
									<p class="text-white/25">missing</p>
									<p class="mt-0.5 tabular-nums {feed?.missing ? 'text-amber-300' : 'text-white/55'}">
										{feed?.missing ?? 0}
									</p>
								</div>
								<div>
									<p class="text-white/25">last boot</p>
									<p class="mt-0.5 tabular-nums text-white/55">{ago(feed?.last_boot_at)}</p>
								</div>
								<div class="text-right">
									<p class="text-white/25">roll interval</p>
									<p class="mt-0.5 tabular-nums text-white/55">{feed ? `${(feed.roll_interval_ms / 1000).toFixed(0)}s` : '—'}</p>
								</div>
								<div>
									<p class="text-white/25">re-boots</p>
									<p class="mt-0.5 tabular-nums text-white/55">{feed?.reboot_attempts ?? 0}</p>
								</div>
								<div class="text-right">
									<p class="text-white/25">retry queue</p>
									<p
										class="mt-0.5 tabular-nums {feed?.reboot_permanent
											? 'text-rose-300'
											: feed?.reboot_in_flight || feed?.reboot_pending
												? 'text-amber-300'
												: 'text-white/55'}"
									>
										{feed?.reboot_in_flight ?? 0} / {feed?.reboot_pending ?? 0}
									</p>
								</div>
							</div>
							{#if feed?.missing_series?.length || feed?.reboot_permanent_series?.length}
								<div class="mt-2 space-y-1 rounded-lg border border-amber-400/15 bg-amber-400/[0.04] px-2.5 py-2 text-[10px] text-amber-100/70">
									{#if feed?.missing_series?.length}
										<p>
											<span class="font-medium text-amber-200/90">missing</span>
											{feed.missing_series.slice(0, 4).join(', ')}{feed.missing_series.length > 4
												? ` +${feed.missing_series.length - 4}`
												: ''}
										</p>
									{/if}
									{#if feed?.reboot_permanent_series?.length}
										<p>
											<span class="font-medium text-rose-200/90">blacklisted</span>
											{feed.reboot_permanent_series.slice(0, 4).join(', ')}{feed.reboot_permanent_series.length > 4
												? ` +${feed.reboot_permanent_series.length - 4}`
												: ''}
										</p>
									{/if}
								</div>
							{/if}
							{#if feed?.last_roll_error || feed?.last_error}
								<div class="mt-2 rounded-lg border border-rose-400/15 bg-rose-400/[0.05] px-2.5 py-2 text-[10px] text-rose-200/70">
									<p class="font-medium text-rose-200/90">{feed?.last_error_scope ?? 'feed'} error</p>
									<p class="mt-0.5 break-words">{feed?.last_error ?? feed?.last_roll_error}</p>
								</div>
							{/if}
						</div>
					</section>

					<section class="rounded-2xl border border-white/[0.06] bg-[#0e1016]/90 p-4">
						<div class="flex items-center justify-between">
							<h2 class="text-[10px] font-medium tracking-[0.18em] text-white/35 uppercase">
								market watch
							</h2>
							<span class="text-[10px] text-white/20">{stats?.bucket_tickers ?? 0} live signals</span>
						</div>
						<div class="mt-2 max-h-[300px] space-y-0.5 overflow-auto pr-1">
							{#each market as symbol (symbol.ticker)}
								<div class="flex items-center gap-2 rounded-lg px-1.5 py-2 hover:bg-white/[0.02]">
									<div class="min-w-0 flex-1">
										<div class="flex items-center gap-1.5">
											<span class="text-[11px] font-medium text-white/65">{displayTicker(symbol.ticker)}</span>
											{#if symbol.has_position}
												<span class="rounded bg-indigo-400/10 px-1 py-px text-[8px] font-medium tracking-[0.08em] text-indigo-300 uppercase">pos</span>
											{/if}
										</div>
										<div class="mt-1 h-0.5 overflow-hidden rounded-full bg-white/[0.05]">
											<div
												class="h-full rounded-full {symbol.aggregate_confidence >= (risk?.min_confidence ?? 0.7)
													? 'bg-emerald-400/80'
													: 'bg-indigo-400/50'}"
												style="width:{Math.min(Math.max(symbol.aggregate_confidence * 100, 0), 100)}%"
											></div>
										</div>
									</div>
									<div class="text-right">
										<p class="text-[11px] tabular-nums text-white/65">{price(symbol.price)}</p>
										<p class="text-[9px] tabular-nums text-white/25">{confidence(symbol.aggregate_confidence)}</p>
									</div>
								</div>
							{:else}
								<p class="py-6 text-center text-[11px] text-white/25">waiting for quotes</p>
							{/each}
						</div>
					</section>

					<section class="rounded-2xl border border-white/[0.06] bg-[#0e1016]/90 p-4">
						<div class="flex items-center justify-between">
							<h2 class="text-[10px] font-medium tracking-[0.18em] text-white/35 uppercase">
								latest signals
							</h2>
							<span class="text-[10px] text-white/20">redis → bucket</span>
						</div>
						<div class="mt-2 max-h-[260px] space-y-1 overflow-auto pr-1">
							{#each signals.slice(0, 12) as signal (signal.at + signal.ticker + signal.strategy)}
								<div class="flex items-center justify-between gap-2 rounded-lg px-1.5 py-1.5">
									<div class="min-w-0">
										<div class="flex items-center gap-1.5">
											<span class="text-[11px] text-white/65">{displayTicker(signal.ticker)}</span>
											<span class="text-[9px] text-white/25">{signal.strategy}</span>
										</div>
										<p class="mt-0.5 text-[9px] text-white/20">{signal.size} · {ago(signal.at)}</p>
									</div>
									<span class="rounded bg-white/[0.04] px-1.5 py-0.5 text-[9px] tabular-nums text-white/45">
										{confidence(signal.confidence)}
									</span>
								</div>
							{:else}
								<p class="py-6 text-center text-[11px] text-white/25">waiting for signals</p>
							{/each}
						</div>
					</section>

					<section class="rounded-2xl border border-white/[0.06] bg-[#0e1016]/90 p-4">
						<h2 class="text-[10px] font-medium tracking-[0.18em] text-white/35 uppercase">
							risk settings
						</h2>
						<div class="mt-3 grid grid-cols-2 gap-x-4 gap-y-3 text-[10px]">
							<div>
								<p class="text-white/25">min confidence</p>
								<p class="mt-0.5 tabular-nums text-white/60">{confidence(risk?.min_confidence)}</p>
							</div>
							<div class="text-right">
								<p class="text-white/25">position cap</p>
								<p class="mt-0.5 tabular-nums text-white/60">{((risk?.max_position_fraction ?? 0) * 100).toFixed(0)}%</p>
							</div>
							<div>
								<p class="text-white/25">stop loss</p>
								<p class="mt-0.5 tabular-nums text-rose-300/80">−{((risk?.stop_loss_percent ?? 0) * 100).toFixed(1)}%</p>
							</div>
							<div class="text-right">
								<p class="text-white/25">take profit</p>
								<p class="mt-0.5 tabular-nums text-emerald-300/80">+{((risk?.take_profit_percent ?? 0) * 100).toFixed(1)}%</p>
							</div>
							<div>
								<p class="text-white/25">max hold</p>
								<p class="mt-0.5 tabular-nums text-white/60">{risk ? duration(risk.hold_duration_ms / 1000) : '—'}</p>
							</div>
							<div class="text-right">
								<p class="text-white/25">re-entry lock</p>
								<p class="mt-0.5 tabular-nums text-white/60">{risk ? duration(risk.rebuy_cooldown_ms / 1000) : '—'}</p>
							</div>
						</div>
					</section>

					{#if data?.errors?.length}
						<section class="rounded-2xl border border-rose-400/10 bg-rose-400/[0.03] p-4">
							<div class="flex items-center justify-between">
								<h2 class="text-[10px] font-medium tracking-[0.18em] text-rose-200/50 uppercase">
									recent errors
								</h2>
								<span class="text-[10px] text-rose-200/25">{data?.errors?.length}</span>
							</div>
							<div class="mt-2 space-y-1.5">
								{#each data?.errors?.slice(0, 4) ?? [] as item (item.at + item.scope)}
									<div class="rounded-lg bg-black/20 px-2 py-1.5">
										<p class="text-[9px] text-rose-200/40">{item.scope} · {ago(item.at)}</p>
										<p class="mt-0.5 break-words text-[10px] text-rose-200/70">{item.error}</p>
									</div>
								{/each}
							</div>
						</section>
					{/if}
				</aside>
			</div>

			<footer class="flex flex-col gap-1 border-t border-white/[0.05] pt-4 text-[10px] text-white/20 sm:flex-row sm:items-center sm:justify-between">
				<span>Tyche gateway status API · paper trading only</span>
				<span class="font-mono">{apiURL}</span>
			</footer>
		</main>
	</div>
</div>
