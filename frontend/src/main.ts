import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { AppComponent } from './app/app';
import { OverviewComponent, PendingComponent } from './app/overview';
import { ConnectionsComponent } from './app/connections';
import { TablesComponent } from './app/tables';
import './oracle.css';
import { MappingComponent } from './app/mapping';
bootstrapApplication(AppComponent,{providers:[provideHttpClient(),provideRouter([
  {path:'',pathMatch:'full',redirectTo:'overview'},
  {path:'overview',component:OverviewComponent},
  {path:'connections',component:ConnectionsComponent},
  {path:'tables',component:TablesComponent},
  {path:'mapping',component:MappingComponent},
  {path:'generation',component:PendingComponent,data:{phase:'02',title:'Fixed-length files',description:'This phase will use your mapping workbook and datasheet to generate Header, Detail and Trailer records, with zero-padded numbers and space-padded text.'}},
  {path:'**',redirectTo:'overview'}
])]}).catch(console.error);
