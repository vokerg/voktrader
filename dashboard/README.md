# VokTrader Dashboard

This is the Angular-based dashboard for VokTrader.

## Prerequisites

- Node.js and npm
- VokTrader backend running on `localhost:8080`

## Getting Started

1. Install dependencies:
   ```bash
   npm install
   ```

2. Run the development server:
   ```bash
   npm start
   ```

The dashboard will be available at `http://localhost:4200`. It is configured to proxy `/api` requests to `http://localhost:8080`.

## Features

- **Dashboard**: Overview of active bots, total trades, and PnL.
- **Bots**: List of all configured bots with pause/resume functionality.
- **Trades**: Detailed history of recent trades.
- **Markets**: View of active and tracked markets.

## Technology Stack

- Angular 21
- Signals for state management
- Standalone components
- Vanilla CSS
