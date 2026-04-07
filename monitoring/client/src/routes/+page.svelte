<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import { Chart, registerables } from 'chart.js';
	Chart.register(...registerables);

	export interface Data {
		LoadedFuncs: number;
		FunctionsRanPS: number;
		ThroughputOfStocks: number;
		DroppedStocks: number;
		CoreCount: number;
		CoreStatus: number[];
		PendingFunctions: number;
		ScheduledFunctions: number;
		ProcessRAMUsage: number;
	}

	const MAX = 50;
	let data = $state<Data | null>(null);
	let prev = $state<Data | null>(null);
	let live = $state(false);
	let time = $state('—');
	let uptick = $state(0);

	const labels = Array(MAX).fill('');
	const fpsData = Array(MAX).fill(null);
	const tpData = Array(MAX).fill(null);
	const dropData = Array(MAX).fill(null);
	const pendData = Array(MAX).fill(null);
	const schedData = Array(MAX).fill(null);
	const ramData = Array(MAX).fill(null);

	let fpsCanvas: HTMLCanvasElement;
	let tpDropCanvas: HTMLCanvasElement;
	let queueCanvas: HTMLCanvasElement;
	let ramCanvas: HTMLCanvasElement;

	let fpsChart: Chart;
	let tpDropChart: Chart;
	let queueChart: Chart;
	let ramChart: Chart;

	let interval: ReturnType<typeof setInterval>;

	const fpsDelta = $derived(data && prev ? data.FunctionsRanPS - prev.FunctionsRanPS : null);
	const tpDelta = $derived(data && prev ? data.ThroughputOfStocks - prev.ThroughputOfStocks : null);
	const dropDelta = $derived(data && prev ? data.DroppedStocks - prev.DroppedStocks : null);
	const ramPct = $derived(
		data ? Math.round((data.ProcessRAMUsage / Math.max(data.ProcessRAMUsage * 1.5, 2048)) * 100) : 0
	);

	function push(arr: (number | null)[], val: number) {
		arr.shift();
		arr.push(val);
	}

	function coreColor(v: number) {
		if (v > 70) return '#f97316';
		if (v > 40) return '#eab308';
		return '#6366f1';
	}

	function deltaClass(d: number | null) {
		if (d === null) return 'text-white/20';
		if (d > 0) return 'text-emerald-400';
		if (d < 0) return 'text-orange-400';
		return 'text-white/20';
	}

	function fmtDelta(d: number | null) {
		if (d === null || d === 0) return '—';
		return (d > 0 ? '+' : '') + d;
	}

	const GRID = 'rgba(255,255,255,0.04)';
	const TICK = '#555';
	const BASE = { responsive: true, maintainAspectRatio: false, animation: { duration: 350 } };
	const NLEG = { legend: { display: false } };
	const XHIDE = { display: false };

	async function tick() {
		try {
			const r = await fetch('http://localhost:8082');
			if (!r.ok) throw new Error();
			prev = data;
			data = (await r.json()) as Data;
			live = true;
			time = new Date().toLocaleTimeString();
			uptick++;

			push(fpsData, data.FunctionsRanPS);
			push(tpData, data.ThroughputOfStocks);
			push(dropData, data.DroppedStocks);
			push(pendData, data.PendingFunctions);
			push(schedData, data.ScheduledFunctions);
			push(ramData, data.ProcessRAMUsage);

			fpsChart?.update('none');
			tpDropChart?.update('none');
			queueChart?.update('none');
			ramChart?.update('none');
		} catch {
			live = false;
		}
	}

	onMount(() => {
		fpsChart = new Chart(fpsCanvas, {
			data: {
				labels,
				datasets: [
					{
						type: 'line',
						data: fpsData,
						borderColor: '#818cf8',
						backgroundColor: 'rgba(129,140,248,0.08)',
						fill: true,
						borderWidth: 2,
						pointRadius: 0,
						tension: 0.4
					}
				]
			},
			options: {
				...BASE,
				plugins: NLEG,
				scales: {
					x: XHIDE,
					y: {
						grid: { color: GRID },
						ticks: { color: TICK, font: { size: 10 } },
						beginAtZero: true
					}
				}
			}
		});

		tpDropChart = new Chart(tpDropCanvas, {
			data: {
				labels,
				datasets: [
					{
						type: 'line',
						label: 'Throughput',
						data: tpData,
						borderColor: '#34d399',
						backgroundColor: 'rgba(52,211,153,0.07)',
						fill: true,
						borderWidth: 2,
						pointRadius: 0,
						tension: 0.4,
						yAxisID: 'y'
					},
					{
						type: 'bar',
						label: 'Dropped',
						data: dropData,
						backgroundColor: 'rgba(249,115,22,0.6)',
						borderRadius: 2,
						barPercentage: 0.5,
						yAxisID: 'y2'
					}
				]
			},
			options: {
				...BASE,
				plugins: NLEG,
				interaction: { mode: 'index', intersect: false },
				scales: {
					x: XHIDE,
					y: {
						position: 'left',
						grid: { color: GRID },
						ticks: { color: TICK, font: { size: 10 } },
						beginAtZero: true
					},
					y2: {
						position: 'right',
						grid: { drawOnChartArea: false },
						ticks: { color: '#f97316', font: { size: 10 } },
						beginAtZero: true
					}
				}
			}
		});

		queueChart = new Chart(queueCanvas, {
			data: {
				labels,
				datasets: [
					{
						type: 'line',
						label: 'Pending',
						data: pendData,
						borderColor: '#a78bfa',
						backgroundColor: 'rgba(167,139,250,0.08)',
						fill: true,
						borderWidth: 2,
						pointRadius: 0,
						tension: 0.4
					},
					{
						type: 'line',
						label: 'Scheduled',
						data: schedData,
						borderColor: '#38bdf8',
						backgroundColor: 'rgba(56,189,248,0.06)',
						fill: true,
						borderWidth: 2,
						pointRadius: 0,
						tension: 0.4
					}
				]
			},
			options: {
				...BASE,
				plugins: NLEG,
				interaction: { mode: 'index', intersect: false },
				scales: {
					x: XHIDE,
					y: {
						grid: { color: GRID },
						ticks: { color: TICK, font: { size: 10 } },
						beginAtZero: true
					}
				}
			}
		});

		ramChart = new Chart(ramCanvas, {
			data: {
				labels,
				datasets: [
					{
						type: 'line',
						data: ramData,
						borderColor: '#f472b6',
						backgroundColor: 'rgba(244,114,182,0.07)',
						fill: true,
						borderWidth: 2,
						pointRadius: 0,
						tension: 0.4
					}
				]
			},
			options: {
				...BASE,
				plugins: NLEG,
				scales: {
					x: XHIDE,
					y: {
						grid: { color: GRID },
						ticks: { color: TICK, font: { size: 10 }, callback: (v: number) => v + ' MB' },
						beginAtZero: true
					}
				}
			}
		});

		tick();
		interval = setInterval(tick, 1000);
	});

	onDestroy(() => {
		clearInterval(interval);
		[fpsChart, tpDropChart, queueChart, ramChart].forEach((c) => c?.destroy());
	});
</script>

<div
	class="flex min-h-screen flex-col gap-5 bg-[#0b0b0d] p-6 text-white"
	style="font-family: -apple-system, BlinkMacSystemFont, 'Inter', sans-serif;"
>
	<!-- header -->
	<div class="flex items-center justify-between border-b border-white/[0.06] pb-4">
		<div class="flex items-center gap-4">
			<span class="text-sm font-medium tracking-[0.12em] text-white/70 uppercase">Tyche Engine</span
			>
			<div class="h-3 w-px bg-white/10"></div>
			<div class="flex items-center gap-2 text-xs {live ? 'text-emerald-400' : 'text-white/25'}">
				<span class="h-1.5 w-1.5 rounded-full {live ? 'bg-emerald-400' : 'bg-white/20'}"></span>
				{live ? 'live' : 'offline'}
			</div>
			{#if live}
				<span class="text-xs text-white/20">tick #{uptick}</span>
			{/if}
		</div>
		<div class="flex items-center gap-6 text-xs text-white/25">
			<span>{data?.CoreCount ?? '—'} cores</span>
			<span>{data?.LoadedFuncs ?? '—'} loaded fns</span>
			<span class="tabular-nums">{time}</span>
		</div>
	</div>

	<!-- top stat row -->
	<div class="grid grid-cols-6 gap-3">
		{#each [{ label: 'fn / sec', value: data?.FunctionsRanPS, delta: fpsDelta, accent: '#818cf8' }, { label: 'throughput', value: data?.ThroughputOfStocks, delta: tpDelta, accent: '#34d399' }, { label: 'dropped', value: data?.DroppedStocks, delta: dropDelta, accent: '#f97316' }, { label: 'pending', value: data?.PendingFunctions, delta: null, accent: '#a78bfa' }, { label: 'scheduled', value: data?.ScheduledFunctions, delta: null, accent: '#38bdf8' }, { label: 'ram (mb)', value: data?.ProcessRAMUsage, delta: null, accent: '#f472b6' }] as s}
			<div class="flex flex-col gap-2 rounded-xl border border-white/[0.05] bg-white/[0.03] p-4">
				<div class="flex items-center justify-between">
					<span class="text-[10px] tracking-widest text-white/25 uppercase">{s.label}</span>
					<span class="h-1.5 w-1.5 rounded-full" style="background:{s.accent}; opacity:0.7"></span>
				</div>
				<span class="text-2xl font-medium tabular-nums" style="color:{s.accent}"
					>{s.value ?? '—'}</span
				>
				{#if s.delta !== null}
					<span class="text-[11px] tabular-nums {deltaClass(s.delta)}">{fmtDelta(s.delta)}</span>
				{:else}
					<span class="text-[11px] text-transparent">·</span>
				{/if}
			</div>
		{/each}
	</div>

	<!-- row 2: fn/sec chart + core meters -->
	<div class="grid grid-cols-5 gap-4">
		<div
			class="col-span-3 flex flex-col gap-3 rounded-xl border border-white/[0.05] bg-white/[0.03] p-5"
		>
			<div class="flex items-center justify-between">
				<span class="text-[10px] tracking-widest text-white/30 uppercase">Functions / sec</span>
				<span class="text-xs text-white/20">50 ticks</span>
			</div>
			<div class="relative" style="height:140px"><canvas bind:this={fpsCanvas}></canvas></div>
		</div>

		<div
			class="col-span-2 flex flex-col gap-4 rounded-xl border border-white/[0.05] bg-white/[0.03] p-5"
		>
			<span class="text-[10px] tracking-widest text-white/30 uppercase">Core load</span>
			<div class="grid flex-1 grid-cols-2 gap-x-8 gap-y-3">
				{#if data}
					{#each data.CoreStatus as load, i}
						<div class="flex flex-col gap-1">
							<div class="flex items-baseline justify-between">
								<span class="text-[10px] text-white/25">core {i}</span>
								<span class="text-[11px] tabular-nums" style="color:{coreColor(load)}">{load}</span>
							</div>
							<div class="h-1 overflow-hidden rounded-full bg-white/[0.05]">
								<div
									class="h-full rounded-full transition-all duration-500"
									style="width:{Math.min(load, 100)}%; background:{coreColor(load)}"
								></div>
							</div>
						</div>
					{/each}
				{:else}
					{#each Array(8) as _}
						<div class="h-1 rounded-full bg-white/[0.05]"></div>
					{/each}
				{/if}
			</div>
		</div>
	</div>

	<!-- row 3: throughput+dropped | queue (pending+scheduled) -->
	<div class="grid grid-cols-2 gap-4">
		<div class="flex flex-col gap-3 rounded-xl border border-white/[0.05] bg-white/[0.03] p-5">
			<div class="flex items-center justify-between">
				<span class="text-[10px] tracking-widest text-white/30 uppercase"
					>Throughput &amp; dropped</span
				>
				<div class="flex items-center gap-4 text-[10px] text-white/30">
					<span class="flex items-center gap-1.5"
						><span class="inline-block h-0.5 w-2.5 rounded bg-emerald-400"></span>throughput</span
					>
					<span class="flex items-center gap-1.5"
						><span class="inline-block h-0.5 w-2.5 rounded bg-orange-400"></span>dropped</span
					>
				</div>
			</div>
			<div class="relative" style="height:150px"><canvas bind:this={tpDropCanvas}></canvas></div>
		</div>

		<div class="flex flex-col gap-3 rounded-xl border border-white/[0.05] bg-white/[0.03] p-5">
			<div class="flex items-center justify-between">
				<span class="text-[10px] tracking-widest text-white/30 uppercase"
					>Queue — pending &amp; scheduled</span
				>
				<div class="flex items-center gap-4 text-[10px] text-white/30">
					<span class="flex items-center gap-1.5"
						><span class="inline-block h-0.5 w-2.5 rounded bg-violet-400"></span>pending</span
					>
					<span class="flex items-center gap-1.5"
						><span class="inline-block h-0.5 w-2.5 rounded bg-sky-400"></span>scheduled</span
					>
				</div>
			</div>
			<div class="relative" style="height:150px"><canvas bind:this={queueCanvas}></canvas></div>
		</div>
	</div>

	<!-- row 4: ram chart + ram gauge -->
	<div class="grid grid-cols-3 gap-4">
		<div
			class="col-span-2 flex flex-col gap-3 rounded-xl border border-white/[0.05] bg-white/[0.03] p-5"
		>
			<span class="text-[10px] tracking-widest text-white/30 uppercase">RAM usage over time</span>
			<div class="relative" style="height:120px"><canvas bind:this={ramCanvas}></canvas></div>
		</div>

		<div
			class="flex flex-col justify-between gap-4 rounded-xl border border-white/[0.05] bg-white/[0.03] p-5"
		>
			<span class="text-[10px] tracking-widest text-white/30 uppercase">RAM now</span>
			<div class="flex flex-col gap-1">
				<span class="text-4xl font-medium text-pink-400 tabular-nums"
					>{data?.ProcessRAMUsage ?? '—'}</span
				>
				<span class="text-xs text-white/25">MB in use</span>
			</div>
			<div class="flex flex-col gap-2">
				<div class="h-1.5 overflow-hidden rounded-full bg-white/[0.05]">
					<div
						class="h-full rounded-full bg-pink-400 transition-all duration-500"
						style="width:{ramPct}%"
					></div>
				</div>
				<div class="flex justify-between text-[10px] text-white/20">
					<span>0</span>
					<span>{ramPct}% of headroom</span>
				</div>
			</div>
		</div>
	</div>
</div>
