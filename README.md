# Tyche
> A distributed, real-time trading system focused on fairness, scalability, and low-latency engineering.

**Tyche** is named after the Greek goddess of fortune, embodying both good and bad luck. (Fun fact: The name was inspired during a God of War gaming session—it just made sense!)<br/>
At its core, **Tyche** is an attempt to build a Fully Autonomous, Real-Time, Distributed, Load-Balanced, and Fair Trading Bot. The focus is heavily on the engineering side: optimizing data flows, ensuring scalability, handling high-volume trades, and all the fun stuff that makes systems robust and efficient.
## Internal Working
![Internal design overview](assets/demo2.png)
The diagram above illustrates the internal design and data flow of the system, showing how the core components interact during execution.

For a detailed explanation of the architecture, design decisions, and implementation details, refer to the accompanying article:
👉 [Let’s Go Gambling – Internal Working Explained](https://medium.com/@itsarnavsh/lets-go-gambling-2101c2a67dac)

## Usage
Start the Java Workers
```bash
cd core
gradle run
```
Start the Golang Client
```bash
cd gateway
go get
go run .
```
## Scope & Non-Goals

Tyche is primarily an engineering and systems exploration project.  
It is **not** intended to be:
- A production-ready trading platform
- A profit-optimized trading strategy
- A consumer-facing trading product

The emphasis is on architecture, concurrency, fault tolerance, and data flow design.
## High-level Architecture

- Java workers handle market simulation, order generation, and trade execution
- The Go gateway acts as the low-latency ingress layer and coordination point
- Communication is event-driven and designed for horizontal scalability
- Load balancing and fairness are enforced at the system level, not strategy level

## Motivation

Most trading bots focus on strategies and profitability.  
Tyche intentionally flips that priority—treating trading as a stress test for distributed systems design.

The project explores:
- Real-time data pipelines
- Backpressure and flow control
- Fairness under concurrent load
- Cross-language system boundaries (Java ↔ Go)

## Project Status
Tyche is an active research/engineering project and is evolving rapidly.
APIs and internal behavior may change without notice.
