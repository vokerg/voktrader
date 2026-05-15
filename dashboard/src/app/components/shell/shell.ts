import { Component } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="layout">
      <aside class="sidebar">
        <div class="logo">
          <h1>VokTrader</h1>
        </div>
        <nav>
          <a routerLink="/dashboard" routerLinkActive="active">Dashboard</a>
          <a routerLink="/bots" routerLinkActive="active">Bots</a>
          <a routerLink="/trades" routerLinkActive="active">Trades</a>
          <a routerLink="/orders" routerLinkActive="active">Orders</a>
          <a routerLink="/markets" routerLinkActive="active">Markets</a>
        </nav>
      </aside>
      <main class="content">
        <header class="top-bar">
          <div class="breadcrumb">VokTrader / Dashboard</div>
        </header>
        <div class="scroll-area">
          <router-outlet />
        </div>
      </main>
    </div>
  `,
  styles: `
    .layout {
      display: flex;
      height: 100vh;
      width: 100vw;
      background-color: #f5f7fa;
      color: #333;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    }

    .sidebar {
      width: 240px;
      background-color: #1a1c23;
      color: #fff;
      display: flex;
      flex-direction: column;
      flex-shrink: 0;
    }

    .logo {
      padding: 24px;
      border-bottom: 1px solid #2d303e;
    }

    .logo h1 {
      margin: 0;
      font-size: 20px;
      font-weight: 700;
      color: #3b82f6;
    }

    nav {
      padding: 16px 0;
      display: flex;
      flex-direction: column;
    }

    nav a {
      padding: 12px 24px;
      color: #9ca3af;
      text-decoration: none;
      transition: all 0.2s;
      font-weight: 500;
    }

    nav a:hover {
      background-color: #2d303e;
      color: #fff;
    }

    nav a.active {
      background-color: #3b82f6;
      color: #fff;
    }

    .content {
      flex: 1;
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }

    .top-bar {
      height: 64px;
      background-color: #fff;
      border-bottom: 1px solid #e5e7eb;
      display: flex;
      align-items: center;
      padding: 0 24px;
      flex-shrink: 0;
    }

    .breadcrumb {
      color: #6b7280;
      font-size: 14px;
    }

    .scroll-area {
      flex: 1;
      overflow-y: auto;
      padding: 24px;
    }
  `
})
export class Shell {}
