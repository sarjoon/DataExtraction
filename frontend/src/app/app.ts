import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Api } from './api';
@Component({selector:'edac-root',standalone:true,imports:[RouterLink,RouterLinkActive,RouterOutlet],template:`
<a class="skip-link" href="#main">Skip to content</a>
<aside class="sidebar">
  <a class="brand" routerLink="/overview" aria-label="Extraction Framework home"><span class="corporate-brand">TECH <span>mahindra</span></span><span class="product-name">Extraction<br>Framework</span></a>
  <div class="workspace-label">DATA ENGINEERING WORKSPACE</div>
  <nav aria-label="Main navigation">
    <a routerLink="/overview" routerLinkActive="active">Overview</a>
    <a routerLink="/connections" routerLinkActive="active">Database connections</a>
    <details class="feature-menu" open><summary>Snapshot</summary>
      <a routerLink="/snapshot/tables" routerLinkActive="active">Table Definition</a>
      <a routerLink="/snapshot/batches" routerLinkActive="active">Batches</a>
      <a routerLink="/snapshot/configuration" routerLinkActive="active">Configuration</a>
      <a routerLink="/snapshot/capture" routerLinkActive="active">Take snapshot</a>
      <a routerLink="/snapshot" routerLinkActive="active" [routerLinkActiveOptions]="{exact:true}">Compare</a>
      <a routerLink="/snapshot/history" routerLinkActive="active">History</a>
      <a routerLink="/snapshot/schema" routerLinkActive="active">Schema Comparison</a>
    </details>
    <details class="feature-menu" open><summary>EDAC Extraction</summary>
      <a routerLink="/tables" routerLinkActive="active">Table list<span class="nav-count">{{api.tables().length}}</span></a>
      <a routerLink="/mapping" routerLinkActive="active">Mapping workbook</a>
      <a routerLink="/generation" routerLinkActive="active">Extraction Scripts</a>
    </details>
    <details class="feature-menu" open><summary>Call Trigger</summary>
      <a routerLink="/triggers" routerLinkActive="active">Complete Call Trigger</a>
    </details>
  </nav>  <div class="sidebar-footer"><span class="status-dot"></span>Local workspace<small>Tech Mahindra · Data engineering</small></div>
</aside>
<div class="workspace">
  <header class="topbar"><span>Extraction Framework</span><span class="environment">{{api.status()?'Configuration DB ready':'Backend offline'}}</span></header>
  <main id="main">
    @if(api.error()){<div class="alert error" role="alert">{{api.error()}}<button class="text-button" (click)="api.init()" [disabled]="api.busy()">Retry connection</button></div>}
    @if(api.message()){<div class="alert success" role="status">{{api.message()}}</div>}
    @if(api.busy()){<div class="activity" role="status">Working…</div>}
    <router-outlet/>
  </main>
  <footer>Extraction Framework <span>Database metadata → Mapping workbook → Extraction scripts</span></footer>
</div>`})
export class AppComponent {api=inject(Api);constructor(){void this.api.init();}}
