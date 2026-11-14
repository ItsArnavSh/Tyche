<script lang="ts">
	import { onMount } from 'svelte';

	interface CacheSummary {
		backend: string;
		ttl_seconds: number;
		hits: number;
		misses: number;
		stored: number;
		errors: string[];
	}

	interface DatasetSummary {
		id: string;
		date: string;
		interval: string;
		source: string;
		start: string;
		end: string;
		warmup_start: string;
		symbols: string[];
		bars: Record<string, number>;
		target_bars: Record<string, number>;
		warmup_bars: Record<string, number>;
		failures: Record<string, string>;
		cache: CacheSummary;
	}

	interface PositionSnapshot {
		symbol: string;
		quantity: number;
		entry_price: number;
		last_price: number;
		market_value: number;
		pnl: number;
		pnl_pct: number;
		bars_held: number;
		stop_loss: number | null;
		take_profit: number | null;
	}

	interface ReplayEvent {
		type: 'buy' | 'sell';
		at: string;
		symbol: string;
		quantity: number;
		price: number;
		fee: number;
		pnl?: number;
		pnl_pct?: number;
		reason: string;
		score?: number;
		bars_held?: number;
	}

	interface ReplayRecord {
		timestamp: string;
		equity: number;
		cash: number;
		positions_value: number;
		exposure: number;
		open_positions: number;
		positions: PositionSnapshot[];
		events: ReplayEvent[];
	}

	interface Trade {
		symbol: string;
		entry_date: string;
		exit_date: string;
		quantity: number;
		entry_price: number;
		exit_price: number;
		fees: number;
		pnl: number;
		return_pct: number;
		reason: string;
		bars_held: number;
	}

	interface ReplayResponse {
		dataset: DatasetSummary;
		strategy: string;
		config: Record<string, number | string | boolean | null>;
		metrics: Record<string, number | null>;
		bars: number;
		first_bar: string | null;
		last_bar: string | null;
		trades: Trade[];
		replay: ReplayRecord[];
	}

	const DEFAULT_SYMBOLS = 'RELIANCE.NS,TCS.NS,HDFCBANK.NS,INFY.NS,ICICIBANK.NS';
	const STRATEGY_DEFAULTS: Record<string, string> = {
		sma: 'fast=20, slow=50',
		rsi: 'period=14, oversold=30, overbought=70',
		macd: 'fast=12, slow=26, signal_period=9',
		bollinger: 'period=20, num_std=2',
		donchian: 'period=20, exit_period=10'
	};
	const SPEEDS = [1, 5, 25, 100, 0];

	let historyURL = $state('http://localhost:8090');
	let date = $state(previousWeekday());
	let symbols = $state(DEFAULT_SYMBOLS);
	let interval = $state('5m');
	let warmupDays = $state(10);
	let strategy = $state('sma');
	let paramsText = $state(STRATEGY_DEFAULTS.sma);
	let cash = $state(100000);
	let maxPositions = $state(10);
	let positionFraction = $state(0.15);
	let stopLoss = $state(0.05);
	let takeProfit = $state(0.1);
	let maxHoldBars = $state(48);
	let feeBps = $state(0);
	let slippageBps = $state(0);
	let closeAtEnd = $state(true);

	let speed = $state(5);
	let playing = $state(false);
	let playhead = $state(0);
	let dataset = $state<DatasetSummary | null>(null);
	let result = $state<ReplayResponse | null>(null);
	let error = $state<string | null>(null);
	let notice = $state<string | null>(null);
	let fetching = $state(false);
	let replaying = $state(false);
	let timer: ReturnType<typeof requestAnimationFrame> | undefined;
	let lastFrameAt = 0;
	let carry = 0;

	const current = $derived(
		result && result.replay.length
			? result.replay[Math.min(playhead, result.replay.length - 1)]
			: null
	);
	const metrics = $derived(result?.metrics ?? null);
	const currentTrades = $derived(
		result?.replay.slice(0, Math.min(playhead + 1, result.replay.length)).flatMap((record) => record.events) ?? []
	);
	const progress = $derived(
		result && result.replay.length > 1 ? (playhead / (result.replay.length - 1)) * 100 : 0
	);
	const chart = $derived.by(() => {
		const records = result?.replay ?? [];
		if (records.length === 0) return { full: '', played: '', area: '' };
		const width = 760;
		const height = 118;
		const pad = 6;
		const values = records.map((record) => record.equity);
		const startValue = Number(result?.metrics?.initial_cash ?? values[0] ?? 0);
		const min = Math.min(...values, startValue);
		const max = Math.max(...values, startValue);
		const span = max - min || 1;
		const point = (value: number, index: number) => {
			const x = pad + (index / Math.max(1, values.length - 1)) * (width - pad * 2);
			const y = height - pad - ((value - min) / span) * (height - pad * 2);
			return `${x.toFixed(1)},${y.toFixed(1)}`;
		};
		const full = values.map(point).join(' ');
		const shown = values.slice(0, Math.max(1, playhead + 1));
		const played = shown.map((value, index) => point(value, index)).join(' ');
		return {
			full,
			played,
			area: `6,${height - pad} ${played} ${point(shown[shown.length - 1] ?? startValue, shown.length - 1).split(',')[0]},${height - pad}`
		};
	});

	onMount(() => {
		const requested = new URLSearchParams(window.location.search).get('history');
		historyURL =
			requested ?? `${window.location.protocol}//${window.location.hostname}:8090`;
		return () => {
			if (timer !== undefined) cancelAnimationFrame(timer);
		};
	});

	function previousWeekday() {
		const day = new Date();
		day.setDate(day.getDate() - 1);
		while (day.getDay() === 0 || day.getDay() === 6) day.setDate(day.getDate() - 1);
		const month = String(day.getMonth() + 1).padStart(2, '0');
		const dateOfMonth = String(day.getDate()).padStart(2, '0');
		return `${day.getFullYear()}-${month}-${dateOfMonth}`;
	}

	function applyStrategyDefaults(event?: Event) {
		if (event) {
			strategy = (event.currentTarget as HTMLSelectElement).value;
		}
		paramsText = STRATEGY_DEFAULTS[strategy] ?? '';
	}

	function parseParams(text: string): Record<string, string | number | boolean> {
		const params: Record<string, string | number | boolean> = {};
		for (const raw of text.split(',')) {
			const item = raw.trim();
			if (!item) continue;
			const separator = item.indexOf('=');
			if (separator <= 0) throw new Error(`strategy params must be key=value, got "${item}"`);
			const key = item.slice(0, separator).trim();
			const value = item.slice(separator + 1).trim();
			if (!key) throw new Error(`empty parameter name in "${item}"`);
			if (value === 'true' || value === 'false') {
				params[key] = value === 'true';
			} else if (value !== '' && !Number.isNaN(Number(value))) {
				params[key] = Number(value);
			} else {
				params[key] = value;
			}
		}
		return params;
	}

	function payload() {
		let params: Record<string, string | number | boolean>;
		try {
			params = parseParams(paramsText);
		} catch (err) {
			throw new Error(err instanceof Error ? err.message : 'invalid strategy params');
		}
		return {
			date,
			symbols,
			interval,
			warmup_days: warmupDays,
			source: 'yahoo',
			strategy,
			params,
			cash,
			max_positions: maxPositions,
			position_fraction: positionFraction,
			stop_loss_pct: stopLoss,
			take_profit_pct: takeProfit,
			max_hold_bars: maxHoldBars,
			fee_bps: feeBps,
			slippage_bps: slippageBps,
			close_at_end: closeAtEnd
		};
	}

	async function request<T>(path: string, body: unknown): Promise<T> {
		const base = historyURL.replace(/\/$/, '');
		const response = await fetch(`${base}${path}`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify(body)
		});
		const text = await response.text();
		let document: { error?: string } = {};
		try {
			document = text ? JSON.parse(text) : {};
		} catch {
			document = { error: text };
		}
		if (!response.ok) {
			throw new Error(document.error || `history API returned ${response.status}`);
		}
		return document as T;
	}

	async function fetchData() {
		fetching = true;
		error = null;
		notice = null;
		try {
			const response = await request<{ dataset: DatasetSummary }>(
				'/api/history/fetch',
				payload()
			);
			dataset = response.dataset;
			notice = `cached ${response.dataset.symbols.length} symbols · ${totalTargetBars(response.dataset)} session bars`;
		} catch (err) {
			error = err instanceof Error ? err.message : 'could not fetch history data';
		} finally {
			fetching = false;
		}
	}

	async function runReplay() {
		replaying = true;
		error = null;
		notice = null;
		pause();
		try {
			const response = await request<ReplayResponse>('/api/history/replay', payload());
			result = response;
			dataset = response.dataset;
			playhead = 0;
			notice = `${response.strategy} · ${response.bars} session bars · ${response.trades.length} trades`;
			startPlayback();
		} catch (err) {
			error = err instanceof Error ? err.message : 'could not replay this session';
		} finally {
			replaying = false;
		}
	}

	function startPlayback() {
		if (!result || result.replay.length === 0) return;
		if (speed === 0) {
			playhead = result.replay.length - 1;
			playing = false;
			return;
		}
		if (playhead >= result.replay.length - 1) playhead = 0;
		playing = true;
		lastFrameAt = 0;
		carry = 0;
		if (timer !== undefined) cancelAnimationFrame(timer);
		timer = requestAnimationFrame(tick);
	}

	function tick(now: number) {
		if (!result || !playing) return;
		if (lastFrameAt === 0) lastFrameAt = now;
		const elapsed = now - lastFrameAt;
		lastFrameAt = now;
		carry += (elapsed * speed) / 1000;
		const step = Math.floor(carry);
		if (step > 0) {
			carry -= step;
			playhead = Math.min(result.replay.length - 1, playhead + step);
		}
		if (playhead >= result.replay.length - 1) {
			playing = false;
			return;
		}
		timer = requestAnimationFrame(tick);
	}

	function pause() {
		playing = false;
		if (timer !== undefined) cancelAnimationFrame(timer);
		timer = undefined;
	}

	function resetPlayback() {
		pause();
		playhead = 0;
	}

	function setSpeed(value: number) {
		speed = value;
		if (playing) startPlayback();
	}

	function seek(value: number) {
		pause();
		playhead = Math.max(0, Math.min(value, (result?.replay.length ?? 1) - 1));
	}

	function onSeek(event: Event) {
		const input = event.currentTarget as HTMLInputElement;
		seek(Number(input.value));
	}

	function totalTargetBars(value: DatasetSummary | null) {
		if (!value) return 0;
		return Object.values(value.target_bars).reduce((sum, count) => sum + count, 0);
	}

	function symbolCount() {
		return String(symbols).split(',').filter((item) => item.trim()).length;
	}

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

	function percent(value: number | null | undefined, digits = 2) {
		if (value === null || value === undefined || Number.isNaN(value)) return '—';
		return `${value >= 0 ? '+' : ''}${(value * 100).toFixed(digits)}%`;
	}

	function tone(value: number | null | undefined) {
		if (value === null || value === undefined || value === 0) return 'text-white/60';
		return value > 0 ? 'text-emerald-400' : 'text-rose-400';
	}

	function clock(value: string | null | undefined) {
		if (!value) return '—';
		const stamp = new Date(value);
		if (Number.isNaN(stamp.getTime())) return '—';
		return stamp.toLocaleTimeString([], {
			hour: '2-digit',
			minute: '2-digit',
			second: '2-digit',
			timeZone: 'Asia/Kolkata'
		});
	}

	function shortTicker(value: string) {
		return value.replace(/\.NS$/, '');
	}
</script>

<svelte:head>
	<title>Tyche — History Replay</title>
	<meta
		name="description"
		content="Fetch a trading session, cache it, and replay it through the same strategy and risk arguments at configurable speed."
	/>
</svelte:head>

<div class="min-h-screen bg-[#08090c] text-white">
	<div
		class="pointer-events-none fixed inset-0 bg-[radial-gradient(ellipse_at_top,rgba(129,140,248,0.08),transparent_45%),radial-gradient(ellipse_at_bottom_right,rgba(52,211,153,0.06),transparent_40%)]"
	></div>

	<div class="relative mx-auto max-w-[1680px] px-4 py-5 sm:px-6 lg:px-8">
		<header
			class="flex flex-col gap-4 border-b border-white/[0.06] pb-5 sm:flex-row sm:items-center sm:justify-between"
		>
			<div class="flex items-center gap-3">
				<div
					class="flex h-9 w-9 items-center justify-center rounded-xl border border-indigo-400/20 bg-indigo-400/10"
				>
					<span class="text-sm font-semibold tracking-tight text-indigo-300">H</span>
				</div>
				<div>
					<div class="flex items-center gap-2">
						<h1 class="text-sm font-semibold tracking-[0.18em] text-white/85 uppercase">Tyche</h1>
						<span
							class="rounded-full border border-indigo-400/20 bg-indigo-400/10 px-2 py-0.5 text-[9px] font-medium tracking-[0.14em] text-indigo-300 uppercase"
							>history</span
						>
					</div>
					<p class="mt-0.5 text-[11px] text-white/35">
						One session, cached, replayed through the backtest engine
					</p>
				</div>
			</div>

			<div class="flex flex-wrap items-center gap-x-4 gap-y-2 text-[11px] text-white/40">
				<a
					href="/"
					class="rounded-md border border-white/[0.08] bg-white/[0.03] px-2.5 py-1 text-white/60 transition hover:border-white/20 hover:text-white"
					>live dashboard</a
				>
				<span class="hidden h-3 w-px bg-white/10 sm:block"></span>
				<span class="font-mono text-[10px] text-white/30">{historyURL}</span>
				<span class="rounded-md border border-white/[0.06] bg-white/[0.03] px-2 py-1 tabular-nums"
					>{symbolCount()} symbols · {interval}</span
				>
			</div>
		</header>

		{#if error}
			<div
				class="mt-4 flex items-start justify-between gap-4 rounded-xl border border-rose-400/20 bg-rose-400/[0.06] px-4 py-3 text-xs text-rose-200"
			>
				<span>{error}</span>
				<span class="font-mono text-[10px] text-rose-200/50">history API</span>
			</div>
		{/if}
		{#if notice}
			<div
				class="mt-4 flex items-start justify-between gap-4 rounded-xl border border-indigo-400/20 bg-indigo-400/[0.06] px-4 py-3 text-xs text-indigo-100"
			>
				<span>{notice}</span>
				{#if dataset}
					<span class="font-mono text-[10px] text-indigo-200/50">{dataset.id}</span>
				{/if}
			</div>
		{/if}

		<main class="mt-5 space-y-5">
			<section class="rounded-2xl border border-white/[0.06] bg-[#0e1016]/90 p-4">
				<div class="flex flex-wrap items-center justify-between gap-3">
					<div>
						<p class="text-[10px] font-medium tracking-[0.18em] text-white/30 uppercase">session setup</p>
						<p class="mt-1 text-xs text-white/40">
							Fetch a warmup window for indicators, then execute only the selected session.
						</p>
					</div>
					<div class="flex flex-wrap items-center gap-2">
						<button
							type="button"
							onclick={fetchData}
							disabled={fetching || replaying}
							class="rounded-lg border border-indigo-400/25 bg-indigo-400/10 px-3 py-2 text-[11px] font-medium text-indigo-200 transition hover:border-indigo-300/50 hover:bg-indigo-400/15 disabled:cursor-wait disabled:opacity-50"
						>
							{fetching ? 'fetching…' : 'fetch & cache'}
						</button>
						<button
							type="button"
							onclick={runReplay}
							disabled={replaying || fetching}
							class="rounded-lg border border-emerald-400/25 bg-emerald-400/10 px-3 py-2 text-[11px] font-medium text-emerald-200 transition hover:border-emerald-300/50 hover:bg-emerald-400/15 disabled:cursor-wait disabled:opacity-50"
						>
							{replaying ? 'replaying…' : 'fetch + replay'}
						</button>
					</div>
				</div>

				<div class="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
					<label class="block">
						<span class="mb-1 block text-[10px] tracking-[0.14em] text-white/35 uppercase">date</span>
						<input
							type="date"
							bind:value={date}
							class="w-full rounded-lg border border-white/[0.08] bg-black/20 px-3 py-2 text-xs text-white outline-none transition focus:border-indigo-400/40"
						/>
					</label>
					<label class="block">
						<span class="mb-1 block text-[10px] tracking-[0.14em] text-white/35 uppercase">interval</span>
						<select
							bind:value={interval}
							class="w-full rounded-lg border border-white/[0.08] bg-black/20 px-3 py-2 text-xs text-white outline-none focus:border-indigo-400/40"
						>
							<option value="1m">1 minute</option>
							<option value="5m">5 minutes</option>
							<option value="15m">15 minutes</option>
							<option value="30m">30 minutes</option>
							<option value="60m">60 minutes</option>
						</select>
					</label>
					<label class="block">
						<span class="mb-1 block text-[10px] tracking-[0.14em] text-white/35 uppercase">warmup days</span>
						<input
							type="number"
							min="0"
							max="365"
							bind:value={warmupDays}
							class="w-full rounded-lg border border-white/[0.08] bg-black/20 px-3 py-2 text-xs text-white outline-none focus:border-indigo-400/40"
						/>
					</label>
					<label class="block">
						<span class="mb-1 block text-[10px] tracking-[0.14em] text-white/35 uppercase">strategy</span>
						<select
							bind:value={strategy}
							onchange={applyStrategyDefaults}
							class="w-full rounded-lg border border-white/[0.08] bg-black/20 px-3 py-2 text-xs text-white outline-none focus:border-indigo-400/40"
						>
							<option value="sma">SMA crossover</option>
							<option value="rsi">RSI reversion</option>
							<option value="macd">MACD trend</option>
							<option value="bollinger">Bollinger reversion</option>
							<option value="donchian">Donchian breakout</option>
						</select>
					</label>
				</div>

				<div class="mt-3 grid gap-3 lg:grid-cols-3">
					<label class="block lg:col-span-2">
						<span class="mb-1 block text-[10px] tracking-[0.14em] text-white/35 uppercase"
							>symbols (comma separated, .NS added automatically)</span
						>
						<input
							type="text"
							bind:value={symbols}
							class="w-full rounded-lg border border-white/[0.08] bg-black/20 px-3 py-2 font-mono text-[11px] text-white outline-none transition focus:border-indigo-400/40"
						/>
					</label>
					<label class="block">
						<span class="mb-1 block text-[10px] tracking-[0.14em] text-white/35 uppercase"
							>strategy params</span
						>
						<input
							type="text"
							bind:value={paramsText}
							class="w-full rounded-lg border border-white/[0.08] bg-black/20 px-3 py-2 font-mono text-[11px] text-white outline-none transition focus:border-indigo-400/40"
						/>
					</label>
				</div>

				<details class="mt-3 rounded-xl border border-white/[0.05] bg-black/10">
					<summary class="cursor-pointer px-3 py-2 text-[10px] tracking-[0.14em] text-white/35 uppercase">
						risk & execution arguments
					</summary>
					<div class="grid gap-3 border-t border-white/[0.05] p-3 sm:grid-cols-2 lg:grid-cols-4">
						<label class="block">
							<span class="mb-1 block text-[10px] text-white/35">initial cash</span>
							<input
								type="number"
								min="1"
								bind:value={cash}
								class="w-full rounded-lg border border-white/[0.08] bg-black/20 px-3 py-2 text-xs text-white outline-none focus:border-indigo-400/40"
							/>
						</label>
						<label class="block">
							<span class="mb-1 block text-[10px] text-white/35">max positions</span>
							<input
								type="number"
								min="1"
								bind:value={maxPositions}
								class="w-full rounded-lg border border-white/[0.08] bg-black/20 px-3 py-2 text-xs text-white outline-none focus:border-indigo-400/40"
							/>
						</label>
						<label class="block">
							<span class="mb-1 block text-[10px] text-white/35">position fraction</span>
							<input
								type="number"
								min="0.01"
								max="1"
								step="0.01"
								bind:value={positionFraction}
								class="w-full rounded-lg border border-white/[0.08] bg-black/20 px-3 py-2 text-xs text-white outline-none focus:border-indigo-400/40"
							/>
						</label>
						<label class="block">
							<span class="mb-1 block text-[10px] text-white/35">max hold bars (0 = off)</span>
							<input
								type="number"
								min="0"
								bind:value={maxHoldBars}
								class="w-full rounded-lg border border-white/[0.08] bg-black/20 px-3 py-2 text-xs text-white outline-none focus:border-indigo-400/40"
							/>
						</label>
						<label class="block">
							<span class="mb-1 block text-[10px] text-white/35">stop loss (0.05 = 5%)</span>
							<input
								type="number"
								min="0"
								max="0.99"
								step="0.01"
								bind:value={stopLoss}
								class="w-full rounded-lg border border-white/[0.08] bg-black/20 px-3 py-2 text-xs text-white outline-none focus:border-indigo-400/40"
							/>
						</label>
						<label class="block">
							<span class="mb-1 block text-[10px] text-white/35">take profit (0.10 = 10%)</span>
							<input
								type="number"
								min="0"
								step="0.01"
								bind:value={takeProfit}
								class="w-full rounded-lg border border-white/[0.08] bg-black/20 px-3 py-2 text-xs text-white outline-none focus:border-indigo-400/40"
							/>
						</label>
						<label class="block">
							<span class="mb-1 block text-[10px] text-white/35">fee bps</span>
							<input
								type="number"
								min="0"
								bind:value={feeBps}
								class="w-full rounded-lg border border-white/[0.08] bg-black/20 px-3 py-2 text-xs text-white outline-none focus:border-indigo-400/40"
							/>
						</label>
						<label class="block">
							<span class="mb-1 block text-[10px] text-white/35">slippage bps</span>
							<input
								type="number"
								min="0"
								bind:value={slippageBps}
								class="w-full rounded-lg border border-white/[0.08] bg-black/20 px-3 py-2 text-xs text-white outline-none focus:border-indigo-400/40"
							/>
						</label>
						<label class="flex items-center gap-2 pt-5 text-[11px] text-white/50">
							<input type="checkbox" bind:checked={closeAtEnd} class="accent-indigo-400" />
							close open positions at session end
						</label>
					</div>
				</details>

				{#if dataset}
					<div class="mt-3 grid gap-2 text-[10px] text-white/35 sm:grid-cols-2 lg:grid-cols-4">
						<div class="rounded-lg border border-white/[0.05] bg-black/10 px-3 py-2">
							<div class="text-white/25 uppercase tracking-[0.12em]">session bars</div>
							<div class="mt-0.5 font-mono text-xs text-white/70">{totalTargetBars(dataset)}</div>
						</div>
						<div class="rounded-lg border border-white/[0.05] bg-black/10 px-3 py-2">
							<div class="text-white/25 uppercase tracking-[0.12em]">cached symbols</div>
							<div class="mt-0.5 font-mono text-xs text-white/70">{dataset.symbols.length}</div>
						</div>
						<div class="rounded-lg border border-white/[0.05] bg-black/10 px-3 py-2">
							<div class="text-white/25 uppercase tracking-[0.12em]">cache hits / misses</div>
							<div class="mt-0.5 font-mono text-xs text-white/70">
								{dataset.cache.hits} / {dataset.cache.misses}
							</div>
						</div>
						<div class="rounded-lg border border-white/[0.05] bg-black/10 px-3 py-2">
							<div class="text-white/25 uppercase tracking-[0.12em]">cache backend</div>
							<div class="mt-0.5 font-mono text-xs {dataset.cache.errors.length ? 'text-amber-300' : 'text-emerald-300'}">
								{dataset.cache.backend}{dataset.cache.errors.length ? ' · degraded' : ' · healthy'}
							</div>
						</div>
					</div>
					{#if Object.keys(dataset.failures).length}
						<div class="mt-2 rounded-lg border border-amber-400/20 bg-amber-400/[0.05] px-3 py-2 text-[10px] text-amber-200">
							missing:
							{#each Object.entries(dataset.failures) as [symbol, reason]}
								<span class="mr-2 font-mono">{symbol}</span><span class="text-amber-200/50">{reason}</span>
							{/each}
						</div>
					{/if}
				{/if}
			</section>

			{#if result}
				<section class="grid gap-3 sm:grid-cols-2 lg:grid-cols-6">
					<div class="rounded-2xl border border-white/[0.06] bg-[#0e1016]/90 p-4 lg:col-span-2">
						<p class="text-[10px] tracking-[0.18em] text-white/30 uppercase">replay equity</p>
						<p class="mt-1 text-3xl font-medium tracking-tight tabular-nums">
							{money(current?.equity ?? metrics?.final_equity, 2)}
						</p>
						<div class="mt-2 flex items-center justify-between text-[10px] text-white/30">
							<span>{clock(current?.timestamp)}</span>
							<span class="tabular-nums {tone((current?.equity ?? 0) - Number(metrics?.initial_cash ?? 0))}">
								{percent(((current?.equity ?? 0) / Number(metrics?.initial_cash ?? 1)) - 1)}
							</span>
						</div>
						<div class="mt-3 h-[88px]">
							<svg viewBox="0 0 760 118" class="h-full w-full" preserveAspectRatio="none">
								<polyline
									points={chart.full}
									fill="none"
									stroke="rgba(255,255,255,0.10)"
									stroke-width="1.5"
								></polyline>
								<polygon points={chart.area} fill="rgba(129,140,248,0.10)"></polygon>
								<polyline
									points={chart.played}
									fill="none"
									stroke="rgba(129,140,248,0.9)"
									stroke-width="2"
									stroke-linejoin="round"
								></polyline>
							</svg>
						</div>
					</div>
					{#each [
						{ label: 'total return', value: percent(metrics?.total_return), sub: 'session close' },
						{ label: 'realised p/l', value: money(metrics?.gross_profit != null ? Number(metrics.gross_profit) - Number(metrics.gross_loss) : null, 2), sub: `${metrics?.total_trades ?? 0} closed trades` },
						{ label: 'win rate', value: `${(Number(metrics?.win_rate ?? 0) * 100).toFixed(1)}%`, sub: `${metrics?.wins ?? 0}w · ${metrics?.losses ?? 0}l` },
						{ label: 'max drawdown', value: percent(-(Number(metrics?.max_drawdown ?? 0))), sub: 'within session' }
					] as card}
						<div class="rounded-2xl border border-white/[0.06] bg-[#0e1016]/90 p-4">
							<p class="text-[10px] tracking-[0.18em] text-white/30 uppercase">{card.label}</p>
							<p class="mt-1 text-lg font-medium tabular-nums {tone(metrics?.total_return)}">
								{card.value}
							</p>
							<p class="mt-1 text-[10px] text-white/30">{card.sub}</p>
						</div>
					{/each}
					<div class="rounded-2xl border border-white/[0.06] bg-[#0e1016]/90 p-4">
						<p class="text-[10px] tracking-[0.18em] text-white/30 uppercase">cash</p>
						<p class="mt-1 text-lg font-medium tabular-nums">{money(current?.cash, 2)}</p>
						<p class="mt-1 text-[10px] text-white/30">
							{current?.open_positions ?? 0} open · {(Number(current?.exposure ?? 0) * 100).toFixed(0)}% exposed
						</p>
					</div>
				</section>

				<section class="rounded-2xl border border-white/[0.06] bg-[#0e1016]/90 p-4">
					<div class="flex flex-wrap items-center justify-between gap-3">
						<div class="flex items-center gap-2">
							<button
								type="button"
								onclick={() => (playing ? pause() : startPlayback())}
								class="rounded-lg border border-white/[0.1] bg-white/[0.05] px-3 py-2 text-xs text-white/80 transition hover:bg-white/[0.09]"
							>
								{playing ? 'pause' : 'play'}
							</button>
							<button
								type="button"
								onclick={resetPlayback}
								class="rounded-lg border border-white/[0.08] bg-white/[0.03] px-3 py-2 text-xs text-white/55 transition hover:text-white"
							>
								reset
							</button>
							<span class="ml-2 font-mono text-[11px] text-white/45">
								{clock(current?.timestamp)} / {clock(result.last_bar)}
							</span>
						</div>
						<div class="flex flex-wrap items-center gap-1.5">
							<span class="mr-1 text-[10px] tracking-[0.12em] text-white/30 uppercase">bars / sec</span>
							{#each SPEEDS as option}
								<button
									type="button"
									onclick={() => setSpeed(option)}
									class="rounded-md border px-2 py-1 text-[10px] tabular-nums transition {speed === option
										? 'border-indigo-400/40 bg-indigo-400/15 text-indigo-200'
										: 'border-white/[0.07] bg-white/[0.02] text-white/40 hover:text-white/70'}"
								>
									{option === 0 ? 'max' : `${option}×`}
								</button>
							{/each}
						</div>
					</div>
					<div class="mt-3 flex items-center gap-3">
						<span class="w-12 text-right font-mono text-[10px] text-white/30">{Math.round(progress)}%</span>
						<input
							type="range"
							min="0"
							max={Math.max(0, result.replay.length - 1)}
							value={playhead}
							oninput={onSeek}
							class="h-1 w-full accent-indigo-400"
						/>
						<span class="w-16 font-mono text-[10px] text-white/30">{playhead + 1}/{result.replay.length}</span>
					</div>
				</section>

				<section class="grid gap-3 lg:grid-cols-2">
					<div class="rounded-2xl border border-white/[0.06] bg-[#0e1016]/90">
						<div class="flex items-center justify-between border-b border-white/[0.05] px-4 py-3">
							<p class="text-[10px] tracking-[0.18em] text-white/30 uppercase">open positions</p>
							<span class="text-[10px] text-white/25">marked at {clock(current?.timestamp)}</span>
						</div>
						{#if current && current.positions.length}
							<div class="divide-y divide-white/[0.04]">
								{#each current.positions as position}
									<div class="grid grid-cols-[1.2fr_repeat(4,0.8fr)] items-center gap-2 px-4 py-3 text-xs">
										<div>
											<div class="font-mono text-white/80">{shortTicker(position.symbol)}</div>
											<div class="mt-0.5 text-[10px] text-white/25">{position.quantity} qty · {position.bars_held} bars</div>
										</div>
										<div class="text-right tabular-nums text-white/55">{money(position.entry_price, 2)}</div>
										<div class="text-right tabular-nums text-white/70">{money(position.last_price, 2)}</div>
										<div class="text-right tabular-nums text-white/55">{money(position.market_value, 2)}</div>
										<div class="text-right tabular-nums {tone(position.pnl)}">{percent(position.pnl_pct)}</div>
									</div>
								{/each}
							</div>
						{:else}
							<div class="px-4 py-8 text-center text-[11px] text-white/25">no open positions at this bar</div>
						{/if}
					</div>

					<div class="rounded-2xl border border-white/[0.06] bg-[#0e1016]/90">
						<div class="flex items-center justify-between border-b border-white/[0.05] px-4 py-3">
							<p class="text-[10px] tracking-[0.18em] text-white/30 uppercase">fill tape</p>
							<span class="text-[10px] text-white/25">latest {Math.min(12, currentTrades.length)} events</span>
						</div>
						{#if currentTrades.length}
							<div class="divide-y divide-white/[0.04]">
								{#each currentTrades.slice(-12).reverse() as event}
									<div class="flex items-center justify-between gap-3 px-4 py-2.5 text-xs">
										<div class="flex items-center gap-2">
											<span class="rounded px-1.5 py-0.5 text-[9px] font-medium tracking-[0.08em] uppercase {event.type === 'buy' ? 'bg-emerald-400/10 text-emerald-300' : 'bg-rose-400/10 text-rose-300'}">{event.type}</span>
											<span class="font-mono text-white/70">{shortTicker(event.symbol)}</span>
											<span class="text-[10px] text-white/30">{event.quantity} @ {money(event.price, 2)}</span>
										</div>
										<div class="flex items-center gap-2 text-[10px]">
											<span class="text-white/30">{clock(event.at)}</span>
											{#if event.pnl !== undefined}
												<span class="tabular-nums {tone(event.pnl)}">{money(event.pnl, 2)}</span>
											{/if}
											<span class="text-white/25">{event.reason}</span>
										</div>
									</div>
								{/each}
							</div>
						{:else}
							<div class="px-4 py-8 text-center text-[11px] text-white/25">no fills yet</div>
						{/if}
					</div>
				</section>

				<section class="rounded-2xl border border-white/[0.06] bg-[#0e1016]/90">
					<div class="flex items-center justify-between border-b border-white/[0.05] px-4 py-3">
						<p class="text-[10px] tracking-[0.18em] text-white/30 uppercase">closed trades</p>
						<span class="text-[10px] text-white/25">{result.trades.length} total</span>
					</div>
					{#if result.trades.length}
						<div class="overflow-x-auto">
							<table class="min-w-full text-left text-[11px]">
								<thead class="text-[9px] tracking-[0.12em] text-white/25 uppercase">
									<tr>
										<th class="px-4 py-2 font-medium">symbol</th>
										<th class="px-3 py-2 font-medium">entry</th>
										<th class="px-3 py-2 font-medium">exit</th>
										<th class="px-3 py-2 font-medium">qty</th>
										<th class="px-3 py-2 font-medium">pnl</th>
										<th class="px-3 py-2 font-medium">return</th>
										<th class="px-3 py-2 font-medium">reason</th>
									</tr>
								</thead>
								<tbody class="divide-y divide-white/[0.04]">
									{#each result.trades as trade}
										<tr>
											<td class="px-4 py-2.5 font-mono text-white/75">{shortTicker(trade.symbol)}</td>
											<td class="px-3 py-2.5 tabular-nums text-white/50">{money(trade.entry_price, 2)}</td>
											<td class="px-3 py-2.5 tabular-nums text-white/50">{money(trade.exit_price, 2)}</td>
											<td class="px-3 py-2.5 tabular-nums text-white/50">{trade.quantity}</td>
											<td class="px-3 py-2.5 tabular-nums {tone(trade.pnl)}">{money(trade.pnl, 2)}</td>
											<td class="px-3 py-2.5 tabular-nums {tone(trade.pnl)}">{percent(trade.return_pct)}</td>
											<td class="px-3 py-2.5 text-white/35">{trade.reason}</td>
										</tr>
									{/each}
								</tbody>
							</table>
						</div>
					{:else}
						<div class="px-4 py-8 text-center text-[11px] text-white/25">
							no closed trades — try a longer warmup or a different strategy
						</div>
					{/if}
				</section>

				<section class="grid gap-3 text-[10px] text-white/30 sm:grid-cols-3">
					<div class="rounded-xl border border-white/[0.05] bg-black/10 px-3 py-2">
						<span class="text-white/20 uppercase tracking-[0.12em]">strategy</span>
						<div class="mt-1 font-mono text-white/60">{result.strategy}</div>
					</div>
					<div class="rounded-xl border border-white/[0.05] bg-black/10 px-3 py-2">
						<span class="text-white/20 uppercase tracking-[0.12em]">session window</span>
						<div class="mt-1 font-mono text-white/60">{clock(result.first_bar)} — {clock(result.last_bar)}</div>
					</div>
					<div class="rounded-xl border border-white/[0.05] bg-black/10 px-3 py-2">
						<span class="text-white/20 uppercase tracking-[0.12em]">cache</span>
						<div class="mt-1 font-mono text-white/60">
							{result.dataset.cache.backend} · hits {result.dataset.cache.hits} · stored {result.dataset.cache.stored}
						</div>
					</div>
				</section>
			{:else}
				<section
					class="rounded-2xl border border-dashed border-white/[0.08] bg-[#0e1016]/50 px-6 py-16 text-center"
				>
					<p class="text-sm text-white/45">Pick a session and replay it.</p>
					<p class="mt-2 text-[11px] text-white/25">
						The fetch warms indicators with prior sessions; execution starts at the selected date.
					</p>
				</section>
			{/if}
		</main>
	</div>
</div>
