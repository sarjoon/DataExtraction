import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { AppComponent } from './app/app';
import { OverviewComponent } from './app/overview';
import { ConnectionsComponent } from './app/connections';
import { TablesComponent } from './app/tables';
import './oracle.css';
import { MappingComponent } from './app/mapping';
import { TriggersComponent } from './app/triggers';
import { SnapshotComponent } from './app/snapshot';
import { ExtractionComponent } from './app/extraction';
bootstrapApplication(AppComponent,{providers:[provideHttpClient(),provideRouter([
  {path:'',pathMatch:'full',redirectTo:'overview'},
  {path:'overview',component:OverviewComponent},
  {path:'connections',component:ConnectionsComponent},
  {path:'tables',component:TablesComponent},
  {path:'mapping',component:MappingComponent},
  {path:'triggers',component:TriggersComponent},
  {path:'snapshot',component:SnapshotComponent,data:{view:'compare'}},
  {path:'snapshot/tables',component:SnapshotComponent,data:{view:'tables'}},
  {path:'snapshot/batches',component:SnapshotComponent,data:{view:'batches'}},
  {path:'snapshot/configuration',component:SnapshotComponent,data:{view:'configuration'}},
  {path:'snapshot/capture',component:SnapshotComponent,data:{view:'capture'}},
  {path:'snapshot/history',component:SnapshotComponent,data:{view:'history'}},
  {path:'snapshot/report/:id/:table',component:SnapshotComponent,data:{view:'report'}},
  {path:'snapshot/report/:id',component:SnapshotComponent,data:{view:'report'}},
  {path:'generation',component:ExtractionComponent},
  {path:'**',redirectTo:'overview'}
])]}).catch(console.error);
