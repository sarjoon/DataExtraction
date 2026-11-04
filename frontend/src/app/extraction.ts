import {Component,computed,effect,inject,signal} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {Api} from './api';
interface Column {name:string;dataType:string;numeric:boolean;deltaType:string;}
interface Delta {mode:string;column:string;lowerSource:string;upperSource:string;initialValue:string;flagValue:string;lowerOperator:string;upperOperator:string;}
interface TableOption {group:string;controlSumColumn:string;delta:Delta;}
@Component({standalone:true,imports:[FormsModule],template:`
<div class="page-heading"><div class="eyebrow">EDAC EXTRACTION</div><h1>Extraction project</h1><p>Generate a Spring Boot source project for an external job scheduler.</p></div>
<section class="panel"><label>Source database connection<select [ngModel]="api.selectedId()" (ngModelChange)="changeConnection($event)">@for(c of api.connections();track c.id){<option [ngValue]="c.id">{{c.name}} — {{c.databaseType}}</option>}</select></label>
@if(api.selected()?.databaseType!=='ORACLE'){<p>Select an Oracle connection to generate an extraction project.</p>}
<p>Only the selected method is generated. BAU worksheets are not extraction inputs. Saved passwords are never exported.</p></section>
<section class="panel"><h2>Project settings</h2><div class="two-column">
<label>Extraction method<select [(ngModel)]="engine"><option value="JDBC">JDBC Cursor</option><option value="SQLCL">SQLcl</option></select></label>
<label>Parallel extraction jobs<input type="number" min="1" [(ngModel)]="parallelJobs"></label>
<label>Delimiter<select [(ngModel)]="delimiterChoice"><option value="BEL">BEL (default)</option><option value="PIPE">Pipe |</option><option value="TAB">Tab</option><option value="COMMA">Comma</option><option value="SPACE">Space</option><option value="CUSTOM">Custom</option></select></label>
@if(delimiterChoice==='CUSTOM'){<label>Custom delimiter<input [(ngModel)]="customDelimiter"></label>}
<label>Output directory<input [(ngModel)]="outputDirectory"></label><label>Delivery directory (optional)<input [(ngModel)]="deliveryDirectory"></label>
<label>File name pattern<input [(ngModel)]="fileNamePattern"></label><label>System code<input [(ngModel)]="systemCode"></label><label>Country code<input [(ngModel)]="countryCode"></label>
@if(engine==='SQLCL'){<label>Installed SQLcl executable path<input [(ngModel)]="sqlclExecutable"></label>}
</div>
@if(engine==='JDBC'){<p>Forward-only cursor, fetch size 2000. Write rows progressively and flush the file buffer after every 2000 rows.</p>}
@else{<p>SQLcl is installed separately. Spring Boot controls its process, disk-backed CSV staging, conversion, logs and exit status.</p>}
<p>Header, Detail and Trailer records are always included. NULL is \\N; values containing the delimiter or quotes are quoted. Text line breaks become spaces. Trailer: row count and optional numeric sum.</p>
<p>Filename tokens: {{'{schema}, {table}, {businessDate}'}}. The scheduler supplies business date, group and request ID when launching the built jar.</p>
<p>Maven coordinates: com.edac:extraction. Parent: com.uob.ufw:uob-ms-starter:3.6.15.2.RELEASE. JDK 17 or 21.</p></section>
<section class="panel"><h2>Tables from the table list</h2><label><input type="checkbox" [checked]="allSelected()" (change)="selectAll($any($event.target).checked)">Select all tables ({{selected().size}} / {{api.tables().length}})</label>
<div class="table-scroll"><table><thead><tr><th>Select</th><th>Schema</th><th>Table</th><th>Update frequency</th></tr></thead><tbody>@for(t of api.tables();track t.id){<tr><td><input type="checkbox" [attr.aria-label]="'Extract '+t.schema+'.'+t.name" [checked]="selected().has(t.id)" (change)="toggle(t.id,$any($event.target).checked)"></td><td>{{t.schema}}</td><td>{{t.name}}</td><td>{{t.updateFrequency}}</td></tr>}</tbody></table></div>
<p>Mapping workbook column exclusions are respected.</p></section>
<section class="panel"><h2>Table rules</h2><label>Table options<select [ngModel]="optionTable" (ngModelChange)="loadColumns($event)"><option [ngValue]="null">Choose a table</option>@for(t of chosenTables();track t.id){<option [ngValue]="t.id">{{t.schema}}.{{t.name}}</option>}</select></label>
@if(optionTable!==null){@let option=options[''+optionTable];<div class="two-column">
<label>Scheduler group<input [(ngModel)]="option.group"></label>
<label>Control sum column<select [(ngModel)]="option.controlSumColumn"><option value="">None</option>@for(c of columns();track c.name){@if(c.numeric){<option [value]="c.name">{{c.name}}</option>}}</select></label>
<label>Extraction rule<select [(ngModel)]="option.delta.mode"><option value="FULL">Full</option><option value="RANGE">Delta range</option><option value="FLAG">Delta flag</option></select></label>
@if(option.delta.mode!=='FULL'){
<label>Delta column<select [ngModel]="option.delta.column" (ngModelChange)="chooseColumn($event)"><option value="">Choose a column</option>@for(c of columns();track c.name){@if(c.deltaType && (option.delta.mode==='FLAG'||c.deltaType!=='TEXT')){<option [value]="c.name">{{c.name}} ({{c.dataType}})</option>}}</select></label>
@if(option.delta.mode==='FLAG'){<label>Flag value<input [(ngModel)]="option.delta.flagValue"></label>}
@else{
<label>Lower bound source<select [(ngModel)]="option.delta.lowerSource"><option value="BUSINESS_DATE">Business date start</option><option value="CHECKPOINT">Last successful checkpoint</option><option value="PARAMETER">Run parameter</option></select></label>
<label>Upper bound source<select [(ngModel)]="option.delta.upperSource"><option value="BUSINESS_DATE_END">Next business date start</option><option value="PARAMETER">Run parameter</option></select></label>
<label>Lower operator<select [(ngModel)]="option.delta.lowerOperator"><option value=">=">Greater than or equal</option><option value=">">Greater than</option></select></label>
<label>Upper operator<select [(ngModel)]="option.delta.upperOperator"><option value="<">Less than</option><option value="<=">Less than or equal</option></select></label>
<label>Initial lower bound (optional)<input [(ngModel)]="option.delta.initialValue"></label>
<p>Run parameters: {{'--extraction.parameters.t'+optionTable+'-from=...'}} and {{'--extraction.parameters.t'+optionTable+'-to=...'}}. Numeric ranges require numeric bounds. Checkpoints advance after publication and delivery.</p>
}}</div>}
<p>Groups default to table update frequency. Full exports have no predicate. Tables do not share a snapshot.</p></section>
<section class="panel"><button class="button" (click)="generate()" [disabled]="api.busy()||!selected().size||api.selected()?.databaseType!=='ORACLE'">Generate source project ZIP</button><p role="status">{{message()}}</p></section>
`})
export class ExtractionComponent {
 api=inject(Api);selected=signal(new Set<number>());columns=signal<Column[]>([]);message=signal('');optionTable:number|null=null;
 engine='JDBC';delimiterChoice='BEL';customDelimiter='||';parallelJobs=1;systemCode='PBI';countryCode='SG';outputDirectory='./output';deliveryDirectory='';fileNamePattern='{schema}_{table}_{businessDate}.dat';sqlclExecutable='/opt/sqlcl/bin/sql';
 options:Record<string,TableOption>={};private current:number|null=null;private version=0;private known=new Set<number>();
 allSelected=computed(()=>this.api.tables().length>0&&this.selected().size===this.api.tables().length);
 chosenTables=computed(()=>this.api.tables().filter(t=>this.selected().has(t.id)));
 constructor(){effect(()=>{const connection=this.api.selectedId(),tables=this.api.tables();if(this.current!==connection){this.current=connection;this.known=new Set();this.options={};this.optionTable=null;this.columns.set([]);this.message.set('');this.version++;}
 const available=new Set(tables.map(t=>t.id)),selection=new Set([...this.selected()].filter(id=>available.has(id)));for(const t of tables){if(!this.known.has(t.id))selection.add(t.id);this.options[''+t.id]??={group:t.updateFrequency||'Daily',controlSumColumn:'',delta:{mode:t.loadStrategy==='Delta'?'RANGE':'FULL',column:'',lowerSource:'BUSINESS_DATE',upperSource:'BUSINESS_DATE_END',initialValue:'',flagValue:'',lowerOperator:'>=',upperOperator:'<'}};}this.known=available;
 if(selection.size!==this.selected().size||[...selection].some(id=>!this.selected().has(id)))this.selected.set(selection);
 });}
 changeConnection(id:number){void this.api.run(()=>this.api.select(id));}
 selectAll(value:boolean){this.selected.set(new Set(value?this.api.tables().map(t=>t.id):[]));this.clearOptions();}
 toggle(id:number,value:boolean){const next=new Set(this.selected());value?next.add(id):next.delete(id);this.selected.set(next);this.clearOptions();}
 clearOptions(){if(this.optionTable!==null&&!this.selected().has(this.optionTable)){this.optionTable=null;this.columns.set([]);this.version++;}}
 loadColumns(id:number|null){this.optionTable=id;this.columns.set([]);const version=++this.version,connection=this.api.selectedId();if(id===null||connection===null)return;void this.api.run(async()=>{const columns=await this.api.get<Column[]>('/connections/'+connection+'/extraction-project/columns/'+id);if(version===this.version&&connection===this.api.selectedId())this.columns.set(columns);});}
 chooseColumn(name:string){if(this.optionTable===null)return;const delta=this.options[''+this.optionTable].delta;delta.column=name;if(this.columns().find(c=>c.name===name)?.deltaType==='NUMBER'){delta.lowerSource='PARAMETER';delta.upperSource='PARAMETER';delta.lowerOperator='>';delta.upperOperator='<=';}}
 delimiter(){return ({BEL:'\u0007',SPACE:' ',TAB:'\t',PIPE:'|',COMMA:','} as Record<string,string>)[this.delimiterChoice]??this.customDelimiter;}
 generate(){const connection=this.api.selectedId();if(connection===null)return;void this.api.run(async()=>{this.message.set('');const ids=[...this.selected()],tableOptions=Object.fromEntries(ids.map(id=>[''+id,this.options[''+id]]));
 const blob=await this.api.downloadExtractionProject(connection,{tableIds:ids,engine:this.engine,delimiter:this.delimiter(),parallelJobs:this.parallelJobs,systemCode:this.systemCode,countryCode:this.countryCode,outputDirectory:this.outputDirectory,deliveryDirectory:this.deliveryDirectory,fileNamePattern:this.fileNamePattern,sqlclExecutable:this.engine==='SQLCL'?this.sqlclExecutable:null,tableOptions});
 const url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download='EDAC_Extraction_'+this.engine+'_Project.zip';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);this.message.set('Source project generated for '+ids.length+' table(s). Configure credentials, build with Maven and launch from the external scheduler.');});}
}
